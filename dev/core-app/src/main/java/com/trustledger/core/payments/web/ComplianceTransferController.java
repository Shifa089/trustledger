package com.trustledger.core.payments.web;

import com.trustledger.core.payments.application.HeldTransferPage;
import com.trustledger.core.payments.application.HeldTransferService;
import com.trustledger.core.payments.application.HeldTransferView;
import com.trustledger.core.payments.domain.TransferStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance review of high-value transfers (FR-P-3, SPEC §5, COMPLIANCE_OFFICER only). {@code SecurityConfig}
 * already restricts {@code /api/*}{@code /compliance/**} to officers; {@code @PreAuthorize} repeats it at the
 * controller. The reviewer is taken from the token, so an officer cannot record someone else as the approver.
 */
@RestController
@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
public class ComplianceTransferController {

    /** Amounts as decimal strings (SPEC §6). No note: the queue carries no personal data. */
    public record ReviewTransferResponse(UUID id, TransferStatus status, UUID senderWalletId, UUID receiverWalletId,
            String amount, String currency, Instant createdAt, UUID reviewedBy, Instant reviewedAt) {

        static ReviewTransferResponse of(HeldTransferView view) {
            return new ReviewTransferResponse(view.transferId(), view.status(), view.senderWalletId(),
                    view.receiverWalletId(), view.amount().toDecimalString(),
                    view.amount().currency().getCurrencyCode(), view.createdAt(), view.reviewedBy(),
                    view.reviewedAt());
        }
    }

    public record ReviewQueueResponse(List<ReviewTransferResponse> items, int page, int size, long totalElements,
            int totalPages) {

        static ReviewQueueResponse of(HeldTransferPage page) {
            return new ReviewQueueResponse(page.items().stream().map(ReviewTransferResponse::of).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }

    private final Callers callers;
    private final HeldTransferService review;

    public ComplianceTransferController(Callers callers, HeldTransferService review) {
        this.callers = callers;
        this.review = review;
    }

    @GetMapping(path = "/compliance/transfers", version = "1")
    ReviewQueueResponse queue(@RequestParam(defaultValue = "HELD_FOR_REVIEW") TransferStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(HeldTransferService.MAX_PAGE_SIZE) int size) {
        return ReviewQueueResponse.of(review.queue(status, page, size));
    }

    @PostMapping(path = "/compliance/transfers/{id}/approve", version = "1")
    ReviewTransferResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ReviewTransferResponse.of(review.approve(id, callers.userId(jwt)));
    }

    @PostMapping(path = "/compliance/transfers/{id}/reject", version = "1")
    ReviewTransferResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ReviewTransferResponse.of(review.reject(id, callers.userId(jwt)));
    }
}
