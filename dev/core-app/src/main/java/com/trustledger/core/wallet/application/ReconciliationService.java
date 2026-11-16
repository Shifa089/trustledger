package com.trustledger.core.wallet.application;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.application.ReconciliationReport.Mismatch;
import com.trustledger.core.wallet.application.ReconciliationReport.Reason;
import com.trustledger.core.wallet.domain.WalletBalanceRow;
import com.trustledger.core.wallet.domain.WalletRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-L-4: compares every wallet's cached {@code balance_minor} with the balance derived from its ledger account.
 * <p>
 * Wallets are read in keyset pages. Each page and its ledger balances are read in <b>one read-only REPEATABLE
 * READ transaction</b>, so both come from the same snapshot: a transfer that commits while the page is being
 * checked (it updates wallet and ledger together, M6) can't show up on one side only and look like a mismatch.
 * <p>
 * Each mismatch is logged (wallet ID and amounts only, no personal data) and counted in
 * {@code trustledger.reconciliation.mismatches{reason}} (Prometheus: {@code
 * trustledger_reconciliation_mismatches_total}). Nothing is corrected automatically: a mismatch means a bug, and
 * the fix is a reviewed ledger entry or wallet correction, never an automatic overwrite.
 * <p>
 * Run it through {@link ReconciliationJob}, which holds the cluster-wide lock.
 */
@Service
public class ReconciliationService {

    public static final String MISMATCH_METRIC = "trustledger.reconciliation.mismatches";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final WalletRepository wallets;
    private final LedgerApi ledger;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final TransactionTemplate snapshot;
    private final Map<Reason, Counter> counters = new EnumMap<>(Reason.class);

    public ReconciliationService(WalletRepository wallets, LedgerApi ledger, ReconciliationProperties properties,
            Clock clock, PlatformTransactionManager transactions, MeterRegistry meters) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.properties = properties;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        for (Reason reason : Reason.values()) {
            counters.put(reason, Counter.builder(MISMATCH_METRIC)
                    .description("Wallets whose cached balance disagrees with the ledger, per reconciliation run")
                    .tag("reason", reason.name())
                    .register(meters));
        }
    }

    private record Page(List<WalletBalanceRow> wallets, Map<String, AccountBalance> balances) {
    }

    /** Checks every wallet once. Not transactional itself: one short snapshot transaction per page. */
    public ReconciliationReport reconcile() {
        Instant startedAt = clock.instant();
        long checked = 0;
        long balanceMismatches = 0;
        long unlinked = 0;
        List<Mismatch> listed = new ArrayList<>();
        boolean truncated = false;

        UUID after = new UUID(0, 0);
        while (true) {
            UUID from = after;
            Page page = snapshot.execute(status -> read(from));
            if (page.wallets().isEmpty()) {
                break;
            }
            for (WalletBalanceRow wallet : page.wallets()) {
                checked++;
                Mismatch mismatch = compare(wallet, page.balances());
                if (mismatch == null) {
                    continue;
                }
                if (mismatch.reason() == Reason.BALANCE) {
                    balanceMismatches++;
                } else {
                    unlinked++;
                }
                counters.get(mismatch.reason()).increment();
                if (listed.size() < properties.maxListedMismatches()) {
                    listed.add(mismatch);
                } else {
                    truncated = true;
                }
            }
            after = page.wallets().getLast().walletId();
            if (page.wallets().size() < properties.batchSize()) {
                break;
            }
        }

        ReconciliationReport report = new ReconciliationReport(checked, balanceMismatches, unlinked, listed,
                truncated, startedAt, clock.instant());
        if (balanceMismatches > 0 || unlinked > 0) {
            log.warn("RECONCILIATION ALERT: {} wallets checked, {} balance mismatches, {} unlinked",
                    checked, balanceMismatches, unlinked);
        } else {
            log.info("Reconciliation clean: {} wallets checked", checked);
        }
        return report;
    }

    private Page read(UUID after) {
        List<WalletBalanceRow> page = wallets.balancesAfter(after, properties.batchSize());
        List<String> codes = page.stream().filter(w -> w.ledgerAccountId() != null)
                .map(w -> AccountCodes.wallet(w.walletId())).toList();
        return new Page(page, ledger.balancesOf(codes));
    }

    /** {@code null} when the wallet agrees with the ledger. */
    private static Mismatch compare(WalletBalanceRow wallet, Map<String, AccountBalance> balances) {
        Money cached = Money.of(wallet.balanceMinor(), wallet.currency());
        AccountBalance ledger = wallet.ledgerAccountId() == null ? null
                : balances.get(AccountCodes.wallet(wallet.walletId()));
        if (ledger == null) {
            log.warn("Reconciliation: wallet {} has no ledger account (linked id {})", wallet.walletId(),
                    wallet.ledgerAccountId());
            return Mismatch.unlinked(wallet.walletId(), cached);
        }
        if (ledger.balanceMinor() == wallet.balanceMinor() && ledger.currency().equals(wallet.currency())) {
            return null;
        }
        log.warn("Reconciliation mismatch: wallet {} cached {} {} but ledger {} {}", wallet.walletId(),
                wallet.balanceMinor(), wallet.currency(), ledger.balanceMinor(), ledger.currency());
        return Mismatch.balance(wallet.walletId(), cached, ledger.balanceMinor(),
                ledger.currency().getCurrencyCode());
    }
}
