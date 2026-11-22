package com.trustledger.core.reporting.application;

/**
 * The raw query parameters of {@code GET /wallets/me/transactions}; {@link TransactionHistoryService} validates them.
 *
 * @param from   {@code yyyy-MM-dd}, first day included (Asia/Kolkata)
 * @param to     {@code yyyy-MM-dd}, last day included (Asia/Kolkata)
 * @param type   {@code TOPUP}, {@code TRANSFER_IN} or {@code TRANSFER_OUT}
 * @param sort   {@code field[,asc|desc]}, offset mode only
 * @param cursor {@code null}: offset mode; empty: the first slice in cursor mode; otherwise the {@code nextCursor}
 *               of a previous response
 */
public record TransactionHistoryQuery(String from, String to, String type, Integer page, Integer size, String sort,
        String cursor) {
}
