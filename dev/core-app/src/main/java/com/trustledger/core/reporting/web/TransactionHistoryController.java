package com.trustledger.core.reporting.web;

import com.trustledger.core.payments.WalletTransactionRecord;
import com.trustledger.core.payments.WalletTransactionType;
import com.trustledger.core.reporting.application.TransactionHistoryQuery;
import com.trustledger.core.reporting.application.TransactionHistoryService;
import com.trustledger.core.reporting.application.TransactionHistoryView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-S-1: {@code GET /wallets/me/transactions} (SPEC §5, CUSTOMER). No wallet ID in the path: the wallet is the
 * caller's, so one customer can never ask for another's (BOLA). {@code SecurityConfig} already limits
 * {@code /api/*}{@code /wallets/**} to customers; {@code @PreAuthorize} repeats it here.
 * <p>
 * Offset mode: {@code page}, {@code size}, {@code sort=createdAt|amount[,asc|desc]} (default newest first).
 * Cursor mode: send {@code cursor=} (empty) for the newest slice, then each response's {@code nextCursor}.
 * Parameters are taken as strings and checked in the service, so every bad value is the same 400 ProblemDetail
 * with {@code errorCode=VALIDATION_FAILED}.
 */
@RestController
@PreAuthorize("hasRole('CUSTOMER')")
public class TransactionHistoryController {

    /**
     * Amounts as decimal strings (SPEC §6). {@code counterpartyWalletId} and {@code note} only on transfers.
     * {@code status} is the transfer or top-up status, e.g. {@code COMPLETED}, {@code HELD_FOR_REVIEW},
     * {@code CAPTURED}.
     */
    public record TransactionResponse(UUID id, WalletTransactionType type, String status, String amount,
            String currency, UUID counterpartyWalletId, String note, Instant createdAt) {

        static TransactionResponse of(WalletTransactionRecord row) {
            return new TransactionResponse(row.id(), row.type(), row.status(), row.amount().toDecimalString(),
                    row.amount().currency().getCurrencyCode(), row.counterpartyWalletId(), row.note(),
                    row.createdAt());
        }
    }

    /** Offset mode fills page and the totals; cursor mode fills nextCursor (absent on the last slice). */
    public record TransactionHistoryResponse(List<TransactionResponse> items, Integer page, int size,
            Long totalElements, Integer totalPages, String nextCursor) {

        static TransactionHistoryResponse of(TransactionHistoryView view) {
            return new TransactionHistoryResponse(view.items().stream().map(TransactionResponse::of).toList(),
                    view.page(), view.size(), view.totalElements(), view.totalPages(), view.nextCursor());
        }
    }

    private final Callers callers;
    private final TransactionHistoryService history;

    public TransactionHistoryController(Callers callers, TransactionHistoryService history) {
        this.callers = callers;
        this.history = history;
    }

    @GetMapping(path = "/wallets/me/transactions", version = "1")
    TransactionHistoryResponse transactions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor) {
        return TransactionHistoryResponse.of(history.history(callers.userId(jwt),
                new TransactionHistoryQuery(from, to, type, page, size, sort, cursor)));
    }
}
