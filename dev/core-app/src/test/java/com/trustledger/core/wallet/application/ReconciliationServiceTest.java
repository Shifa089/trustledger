package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.application.ReconciliationReport.Mismatch;
import com.trustledger.core.wallet.application.ReconciliationReport.Reason;
import com.trustledger.core.wallet.domain.WalletBalanceRow;
import com.trustledger.core.wallet.domain.WalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

class ReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T20:30:00Z");
    private static final UUID START = new UUID(0, 0);

    private final WalletRepository wallets = mock(WalletRepository.class);
    private final LedgerApi ledger = mock(LedgerApi.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ReconciliationService service(int batchSize, int maxListed) {
        ReconciliationProperties properties = new ReconciliationProperties("0 0 2 * * *", ZoneId.of("Asia/Kolkata"),
                batchSize, maxListed, Duration.ofMinutes(30), Duration.ofMinutes(1));
        return new ReconciliationService(wallets, ledger, properties, Clock.fixed(NOW, ZoneOffset.UTC),
                transactions, meters);
    }

    private ReconciliationService service() {
        return service(500, 100);
    }

    /** Wallet IDs in ascending order, like the keyset query returns them. */
    private static UUID id(long n) {
        return new UUID(0, n);
    }

    private static WalletBalanceRow linked(long n, long balanceMinor) {
        return new WalletBalanceRow(id(n), balanceMinor, Money.INR, UUID.randomUUID());
    }

    private static AccountBalance ledgerBalance(long n, long minor, Currency currency) {
        return new AccountBalance(AccountCodes.wallet(id(n)), Direction.CREDIT, minor, currency);
    }

    private static Map<String, AccountBalance> balances(AccountBalance... balances) {
        Map<String, AccountBalance> map = new HashMap<>();
        for (AccountBalance balance : balances) {
            map.put(balance.accountCode(), balance);
        }
        return map;
    }

    private double counted(Reason reason) {
        return meters.counter(ReconciliationService.MISMATCH_METRIC, "reason", reason.name()).count();
    }

    @Test
    void walletsThatAgreeWithTheLedgerGiveACleanReport() {
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of(linked(1, 10_000), linked(2, 0)));
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(1, 10_000, Money.INR),
                ledgerBalance(2, 0, Money.INR)));

        ReconciliationReport report = service().reconcile();

        assertThat(report.walletsChecked()).isEqualTo(2);
        assertThat(report.mismatches()).isZero();
        assertThat(report.unlinked()).isZero();
        assertThat(report.listed()).isEmpty();
        assertThat(report.truncated()).isFalse();
        assertThat(report.startedAt()).isEqualTo(NOW);
        assertThat(report.finishedAt()).isEqualTo(NOW);
        assertThat(counted(Reason.BALANCE)).isZero();
        assertThat(counted(Reason.UNLINKED)).isZero();
    }

    @Test
    void differentBalanceIsAMismatchListedAsDecimalStrings() {
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of(linked(1, 10_050), linked(2, 700)));
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(1, 10_000, Money.INR),
                ledgerBalance(2, 700, Money.INR)));

        ReconciliationReport report = service().reconcile();

        assertThat(report.mismatches()).isEqualTo(1);
        assertThat(report.listed()).containsExactly(
                new Mismatch(id(1), Reason.BALANCE, "INR", "100.50", "100.00"));
        assertThat(counted(Reason.BALANCE)).isEqualTo(1);
    }

    @Test
    void negativeLedgerBalanceIsShownWithItsSign() {
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of(linked(1, 0)));
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(1, -500, Money.INR)));

        ReconciliationReport report = service().reconcile();

        assertThat(report.listed()).singleElement().extracting(Mismatch::ledgerBalance).isEqualTo("-5.00");
    }

    @Test
    void differentCurrencyIsAMismatchEvenWithEqualAmounts() {
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of(linked(1, 100)));
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(1, 100, Currency.getInstance("USD"))));

        ReconciliationReport report = service().reconcile();

        assertThat(report.mismatches()).isEqualTo(1);
        assertThat(report.listed()).singleElement().extracting(Mismatch::currency).isEqualTo("USD");
    }

    @Test
    void walletWithoutLedgerLinkIsUnlinkedAndNotLookedUp() {
        WalletBalanceRow unlinked = new WalletBalanceRow(id(1), 0, Money.INR, null);
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of(unlinked, linked(2, 0)));
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(2, 0, Money.INR)));

        ReconciliationReport report = service().reconcile();

        assertThat(report.unlinked()).isEqualTo(1);
        assertThat(report.mismatches()).isZero();
        assertThat(report.listed()).containsExactly(new Mismatch(id(1), Reason.UNLINKED, "INR", "0.00", null));
        assertThat(counted(Reason.UNLINKED)).isEqualTo(1);
        verify(ledger).balancesOf(List.of(AccountCodes.wallet(id(2))));
    }

    @Test
    void linkedWalletWhoseAccountIsMissingIsUnlinked() {
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of(linked(1, 0)));
        when(ledger.balancesOf(anyList())).thenReturn(Map.of());

        assertThat(service().reconcile().unlinked()).isEqualTo(1);
    }

    @Test
    void pagesThroughEveryWalletByKeyset() {
        when(wallets.balancesAfter(START, 2)).thenReturn(List.of(linked(1, 0), linked(2, 0)));
        when(wallets.balancesAfter(id(2), 2)).thenReturn(List.of(linked(3, 0), linked(4, 5)));
        when(wallets.balancesAfter(id(4), 2)).thenReturn(List.of());
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(1, 0, Money.INR),
                ledgerBalance(2, 0, Money.INR), ledgerBalance(3, 0, Money.INR), ledgerBalance(4, 0, Money.INR)));

        ReconciliationReport report = service(2, 100).reconcile();

        assertThat(report.walletsChecked()).isEqualTo(4);
        assertThat(report.mismatches()).isEqualTo(1);
        verify(wallets).balancesAfter(id(4), 2);
    }

    @Test
    void shortPageEndsTheRunWithoutAnotherQuery() {
        when(wallets.balancesAfter(START, 2)).thenReturn(List.of(linked(1, 0)));
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(1, 0, Money.INR)));

        service(2, 100).reconcile();

        verify(wallets, times(1)).balancesAfter(any(), anyInt());
    }

    @Test
    void listIsCappedButEveryMismatchIsCounted() {
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of(linked(1, 1), linked(2, 2), linked(3, 3)));
        when(ledger.balancesOf(anyList())).thenReturn(balances(ledgerBalance(1, 0, Money.INR),
                ledgerBalance(2, 0, Money.INR), ledgerBalance(3, 0, Money.INR)));

        ReconciliationReport report = service(500, 2).reconcile();

        assertThat(report.mismatches()).isEqualTo(3);
        assertThat(report.listed()).extracting(Mismatch::walletId).containsExactly(id(1), id(2));
        assertThat(report.truncated()).isTrue();
        assertThat(counted(Reason.BALANCE)).isEqualTo(3);
    }

    @Test
    void eachPageIsReadInOneReadOnlyRepeatableReadSnapshot() {
        when(wallets.balancesAfter(START, 500)).thenReturn(List.of());

        service().reconcile();

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Test
    void emptyDatabaseIsClean() {
        when(wallets.balancesAfter(eq(START), anyInt())).thenReturn(List.of());

        ReconciliationReport report = service().reconcile();

        assertThat(report.walletsChecked()).isZero();
        assertThat(report.listed()).isEmpty();
    }
}
