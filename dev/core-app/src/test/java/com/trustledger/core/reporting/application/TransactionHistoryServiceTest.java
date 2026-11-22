package com.trustledger.core.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.payments.TransactionFilter;
import com.trustledger.core.payments.TransactionPage;
import com.trustledger.core.payments.TransactionSlice;
import com.trustledger.core.payments.TransactionSort;
import com.trustledger.core.payments.WalletTransactionRecord;
import com.trustledger.core.payments.WalletTransactionType;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.paging.PagingProperties;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();

    @Mock
    WalletApi wallets;

    @Mock
    PaymentsApi payments;

    TransactionHistoryService service;

    @BeforeEach
    void setUp() {
        service = new TransactionHistoryService(wallets, payments, new PagingProperties(20, 100));
    }

    private void hasWallet() {
        Money zero = Money.zero(Money.INR);
        when(wallets.findByUser(USER)).thenReturn(Optional.of(
                new WalletView(WALLET, USER, WalletTier.FULL, WalletStatus.ACTIVE, zero, zero, zero)));
    }

    private static TransactionHistoryQuery query(String from, String to, String type, Integer page, Integer size,
            String sort, String cursor) {
        return new TransactionHistoryQuery(from, to, type, page, size, sort, cursor);
    }

    private static TransactionHistoryQuery offset() {
        return query(null, null, null, null, null, null, null);
    }

    private TransactionFilter offsetFilter(TransactionHistoryQuery query) {
        ArgumentCaptor<TransactionFilter> filter = ArgumentCaptor.forClass(TransactionFilter.class);
        when(payments.transactions(any(), any(), anyInt(), anyInt()))
                .thenReturn(new TransactionPage(List.of(), 0, 20, 0, 0));
        service.history(USER, query);
        verify(payments).transactions(filter.capture(), any(), anyInt(), anyInt());
        return filter.getValue();
    }

    @Test
    void defaultsToTheFirstPageNewestFirstWithTheDefaultSize() {
        hasWallet();
        when(payments.transactions(any(), any(), anyInt(), anyInt()))
                .thenReturn(new TransactionPage(List.of(), 0, 20, 0, 0));

        TransactionHistoryView view = service.history(USER, offset());

        verify(payments).transactions(TransactionFilter.all(WALLET), TransactionSort.NEWEST_FIRST, 0, 20);
        assertThat(view.page()).isZero();
        assertThat(view.nextCursor()).isNull();
    }

    @Test
    void datesAreWholeIndianDaysBothIncluded() {
        hasWallet();

        TransactionFilter filter = offsetFilter(query("2026-11-01", "2026-11-30", null, null, null, null, null));

        // 2026-11-01 00:00 IST = 2026-10-31 18:30 UTC; the day after the 30th starts 2026-11-30 18:30 UTC.
        assertThat(filter.fromInclusive()).isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(filter.toExclusive()).isEqualTo(Instant.parse("2026-11-30T18:30:00Z"));
        assertThat(filter.walletId()).isEqualTo(WALLET);
    }

    @Test
    void aSingleDayIsAllowedAndTypeIsPassedOn() {
        hasWallet();

        TransactionFilter filter = offsetFilter(query("2026-11-10", "2026-11-10", "TRANSFER_IN", null, null, null,
                null));

        assertThat(filter.fromInclusive()).isEqualTo(Instant.parse("2026-11-09T18:30:00Z"));
        assertThat(filter.toExclusive()).isEqualTo(Instant.parse("2026-11-10T18:30:00Z"));
        assertThat(filter.type()).isEqualTo(WalletTransactionType.TRANSFER_IN);
    }

    @Test
    void refusesFromAfterTo() {
        assertValidationFailed(query("2026-11-02", "2026-11-01", null, null, null, null, null));
    }

    @Test
    void refusesMalformedDatesAndTypes() {
        assertValidationFailed(query("01-11-2026", null, null, null, null, null, null));
        assertValidationFailed(query(null, "2026-02-30", null, null, null, null, null));
        assertValidationFailed(query(null, null, "REFUND", null, null, null, null));
        assertValidationFailed(query(null, null, "topup", null, null, null, null));
    }

    @Test
    void refusesPageAndSizeOutsideTheBounds() {
        assertValidationFailed(query(null, null, null, -1, null, null, null));
        assertValidationFailed(query(null, null, null, null, 0, null, null));
        assertValidationFailed(query(null, null, null, null, 101, null, null));
    }

    @Test
    void refusesAnUnknownSortField() {
        assertValidationFailed(query(null, null, null, null, null, "status", null));
    }

    @Test
    void cursorModeCantBeCombinedWithPageOrSort() {
        assertValidationFailed(query(null, null, null, 0, null, null, ""));
        assertValidationFailed(query(null, null, null, null, null, "amount", ""));
    }

    @Test
    void anEmptyCursorReadsTheNewestSliceAndHandsOutTheNextCursor() {
        hasWallet();
        WalletTransactionRecord last = new WalletTransactionRecord(UUID.randomUUID(), WalletTransactionType.TOPUP,
                "CAPTURED", Money.parse("10.00"), null, null, Instant.parse("2026-11-20T10:00:00Z"));
        when(payments.transactionsAfter(any(), eq(null), eq(1)))
                .thenReturn(new TransactionSlice(List.of(last), true));

        TransactionHistoryView view = service.history(USER, query(null, null, null, null, 1, null, ""));

        assertThat(view.page()).isNull();
        assertThat(view.totalElements()).isNull();
        assertThat(HistoryCursor.decode(view.nextCursor())).isEqualTo(TransactionCursor.of(last));
    }

    @Test
    void aGivenCursorIsDecodedAndTheLastSliceHasNoNextCursor() {
        hasWallet();
        TransactionCursor cursor = new TransactionCursor(Instant.parse("2026-11-20T10:00:00Z"), UUID.randomUUID());
        when(payments.transactionsAfter(TransactionFilter.all(WALLET), cursor, 20))
                .thenReturn(new TransactionSlice(List.of(), false));

        TransactionHistoryView view = service.history(USER,
                query(null, null, null, null, null, null, HistoryCursor.encode(cursor)));

        assertThat(view.nextCursor()).isNull();
        assertThat(view.items()).isEmpty();
    }

    @Test
    void aCallerWithoutAWalletGetsKycRequired() {
        when(wallets.findByUser(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.history(USER, offset())).isInstanceOf(TrustLedgerException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.KYC_REQUIRED);
    }

    /** Bad parameters are refused before anything is read. */
    private void assertValidationFailed(TransactionHistoryQuery query) {
        assertThatThrownBy(() -> service.history(USER, query)).isInstanceOf(TrustLedgerException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(payments);
    }
}
