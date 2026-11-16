package com.trustledger.core.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.application.ReconciliationJob;
import com.trustledger.core.wallet.application.ReconciliationReport;
import com.trustledger.core.wallet.application.ReconciliationReport.Mismatch;
import com.trustledger.core.wallet.application.ReconciliationReport.Reason;
import com.trustledger.core.wallet.application.ReconciliationService;
import com.trustledger.core.wallet.application.WalletLedgerLinkService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FR-L-4 end to end: real wallets, real ledger postings through {@code post()}, the real ShedLock table.
 * <p>
 * The test database is shared with every other IT, and some of them credit wallets without a ledger posting
 * (wallet tests that predate M6), so the run sees mismatches that aren't ours. Each test therefore asserts on
 * its own wallets in {@code listed}, never on the global totals.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationIT {

    @Autowired
    WalletFixtures fixtures;

    @Autowired
    WalletLedgerLinkService links;

    @Autowired
    LedgerApi ledger;

    @Autowired
    ReconciliationJob job;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    LockProvider lockProvider;

    @Autowired
    Clock clock;

    /** A linked wallet whose cache and ledger agree: posted to the ledger and credited, like a top-up in M7. */
    private UUID consistentWallet(long minor) {
        UUID walletId = fixtures.wallet(WalletTier.FULL);
        links.link(walletId);
        if (minor > 0) {
            ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                    PostingLine.debit(AccountCodes.GATEWAY_CLEARING, Money.of(minor, Money.INR)),
                    PostingLine.credit(AccountCodes.wallet(walletId), Money.of(minor, Money.INR)))));
            fixtures.fund(walletId, minor);
        }
        return walletId;
    }

    private static List<Mismatch> about(ReconciliationReport report, UUID walletId) {
        assertThat(report.truncated()).as("our wallets must be in the listed part").isFalse();
        return report.listed().stream().filter(m -> m.walletId().equals(walletId)).toList();
    }

    private double counted(Reason reason) {
        return meters.counter(ReconciliationService.MISMATCH_METRIC, "reason", reason.name()).count();
    }

    @Test
    void walletsThatAgreeWithTheLedgerAreNotReported() {
        UUID funded = consistentWallet(25_000);
        UUID empty = consistentWallet(0);

        ReconciliationReport report = job.runNow();

        assertThat(report.walletsChecked()).isGreaterThanOrEqualTo(2);
        assertThat(about(report, funded)).isEmpty();
        assertThat(about(report, empty)).isEmpty();
    }

    /** The M5 "Done when": a deliberately corrupted cached balance is detected, logged and counted. */
    @Test
    void corruptedCachedBalanceIsDetectedAndCounted() {
        UUID corrupted = consistentWallet(25_000);
        UUID healthy = consistentWallet(10_000);
        jdbc.update("update wallet.wallets set balance_minor = balance_minor + 100 where id = ?", corrupted);
        double balanceBefore = counted(Reason.BALANCE);
        double unlinkedBefore = counted(Reason.UNLINKED);

        ReconciliationReport report = job.runNow();

        assertThat(about(report, corrupted)).containsExactly(
                new Mismatch(corrupted, Reason.BALANCE, "INR", "251.00", "250.00"));
        assertThat(about(report, healthy)).isEmpty();
        assertThat(report.mismatches()).isPositive();
        assertThat(counted(Reason.BALANCE) - balanceBefore).isEqualTo(report.mismatches());
        assertThat(counted(Reason.UNLINKED) - unlinkedBefore).isEqualTo(report.unlinked());
    }

    @Test
    void walletWithoutLedgerAccountIsReportedAsUnlinked() {
        UUID walletId = fixtures.wallet(WalletTier.MIN);
        jdbc.update("update wallet.wallets set ledger_account_id = null where id = ?", walletId); // a pre-V7 wallet

        ReconciliationReport report = job.runNow();

        assertThat(about(report, walletId)).containsExactly(
                new Mismatch(walletId, Reason.UNLINKED, "INR", "0.00", null));
        links.link(walletId); // leave it consistent for later runs
    }

    @Test
    void manualRunWhileTheLockIsHeldIsAConflictAndRunsOnceReleased() {
        SimpleLock held = lockProvider.lock(new LockConfiguration(clock.instant(), ReconciliationJob.LOCK_NAME,
                Duration.ofMinutes(5), Duration.ZERO)).orElseThrow();
        try {
            assertThatThrownBy(job::runNow).isInstanceOf(TrustLedgerException.class)
                    .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS);
        } finally {
            held.unlock();
        }

        assertThat(job.runNow().walletsChecked()).isPositive();
    }

    @Test
    void theLockIsARowInTheShedLockTable() {
        job.runNow();

        assertThat(jdbc.queryForObject("select count(*) from shared.shedlock where name = ?", Integer.class,
                ReconciliationJob.LOCK_NAME)).isEqualTo(1);
    }

    @Test
    void nightlyRunUsesTheSameLock() {
        SimpleLock held = lockProvider.lock(new LockConfiguration(clock.instant(), ReconciliationJob.LOCK_NAME,
                Duration.ofMinutes(5), Duration.ZERO)).orElseThrow();
        double before = counted(Reason.BALANCE) + counted(Reason.UNLINKED);
        try {
            job.nightly(); // skipped: no exception, nothing counted
        } finally {
            held.unlock();
        }
        assertThat(counted(Reason.BALANCE) + counted(Reason.UNLINKED)).isEqualTo(before);
    }
}
