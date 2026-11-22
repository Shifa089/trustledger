package com.trustledger.core.reporting.application;

import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.payments.TransactionCursor;
import com.trustledger.core.payments.TransactionFilter;
import com.trustledger.core.payments.TransactionSort;
import com.trustledger.core.payments.WalletTransactionType;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.paging.PagingProperties;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * FR-S-1: the caller's transaction history. The wallet is found from the caller (never from a parameter), so a
 * customer can only ever read their own (BOLA). Every parameter is checked before the database is touched; a bad
 * one is 400 {@code VALIDATION_FAILED}:
 * <ul>
 *   <li>{@code from}/{@code to}: calendar days in Asia/Kolkata, both included, so {@code to=2026-11-30} covers the
 *       whole Indian day; {@code from} after {@code to} is refused.</li>
 *   <li>{@code type}: one of {@link WalletTransactionType}.</li>
 *   <li>{@code page}/{@code size}: the {@code trustledger.paging} bounds, as on every list endpoint.</li>
 *   <li>{@code sort}: the whitelist in {@link HistorySort}; default newest first.</li>
 *   <li>{@code cursor}: switches to keyset mode, which is always newest first, so {@code page} and {@code sort} are
 *       refused with it.</li>
 * </ul>
 * No {@code @Transactional}: it writes nothing, and each API call it makes reads in its own transaction.
 */
@Service
public class TransactionHistoryService {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final WalletApi wallets;
    private final PaymentsApi payments;
    private final PagingProperties paging;

    public TransactionHistoryService(WalletApi wallets, PaymentsApi payments, PagingProperties paging) {
        this.wallets = wallets;
        this.payments = payments;
        this.paging = paging;
    }

    public TransactionHistoryView history(UUID userId, TransactionHistoryQuery query) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(query, "query");
        Window window = windowOf(query);
        WalletTransactionType type = type(query.type());
        int page = query.page() == null ? 0 : query.page();
        int size = paging.checkedSize(page, query.size());
        if (query.cursor() != null) {
            if (query.page() != null || query.sort() != null) {
                throw invalid("cursor can't be combined with page or sort: cursor mode is always newest first.");
            }
            TransactionCursor after = query.cursor().isBlank() ? null : HistoryCursor.decode(query.cursor());
            TransactionFilter filter = new TransactionFilter(walletOf(userId), window.from(), window.to(), type);
            return TransactionHistoryView.of(payments.transactionsAfter(filter, after, size), size);
        }
        TransactionSort sort = HistorySort.parse(query.sort());
        TransactionFilter filter = new TransactionFilter(walletOf(userId), window.from(), window.to(), type);
        return TransactionHistoryView.of(payments.transactions(filter, sort, page, size));
    }

    /** {@code [from 00:00 IST, to + 1 day 00:00 IST)}; either end may be open. */
    private record Window(Instant from, Instant to) {
    }

    private static Window windowOf(TransactionHistoryQuery query) {
        LocalDate from = date("from", query.from());
        LocalDate to = date("to", query.to());
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("from must not be after to.");
        }
        return new Window(from == null ? null : from.atStartOfDay(INDIA).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(INDIA).toInstant());
    }

    private UUID walletOf(UUID userId) {
        return wallets.findByUser(userId).map(WalletView::walletId).orElseThrow(() -> new TrustLedgerException(
                ErrorCode.KYC_REQUIRED, "Complete minimum KYC to get a wallet."));
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be a date as yyyy-MM-dd.");
        }
    }

    private static WalletTransactionType type(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return WalletTransactionType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw invalid("type must be one of " + Arrays.toString(WalletTransactionType.values()) + ".");
        }
    }

    private static TrustLedgerException invalid(String detail) {
        return new TrustLedgerException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
