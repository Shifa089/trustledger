package com.trustledger.core.reporting.application;

import com.trustledger.core.payments.TransactionSort;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code sort} parameter of the history: {@code field} or {@code field,asc|desc}, one field only, from a
 * whitelist. Anything else is 400 {@code VALIDATION_FAILED}: an unchecked sort field would reach the query (an error
 * at best, a sort on an unindexed column at worst). Without a direction, descending (createdAt: newest first;
 * amount: largest first). Absent: newest first.
 */
final class HistorySort {

    /** API name -> sortable field. The keys are the only accepted values. */
    static final Map<String, TransactionSort.Field> WHITELIST = Map.of(
            "createdAt", TransactionSort.Field.CREATED_AT,
            "amount", TransactionSort.Field.AMOUNT);

    private HistorySort() {
    }

    static TransactionSort parse(String sort) {
        if (sort == null || sort.isBlank()) {
            return TransactionSort.NEWEST_FIRST;
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2) {
            throw invalid();
        }
        TransactionSort.Field field = WHITELIST.get(parts[0].trim());
        if (field == null) {
            throw invalid();
        }
        if (parts.length == 1) {
            return new TransactionSort(field, false);
        }
        return switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> new TransactionSort(field, true);
            case "desc" -> new TransactionSort(field, false);
            default -> throw invalid();
        };
    }

    private static TrustLedgerException invalid() {
        return new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                "sort must be one of " + String.join(", ", WHITELIST.keySet().stream().sorted().toList())
                        + ", optionally followed by ,asc or ,desc.");
    }
}
