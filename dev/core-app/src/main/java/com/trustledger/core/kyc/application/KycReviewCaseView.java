package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.masking.Masking;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A KYC case as a compliance officer sees it: the address in clear (they compare it with the address proof),
 * the document list and who decided what. {@code toString()} masks the address.
 */
public record KycReviewCaseView(UUID id, UUID userId, KycLevel level, KycStatus status, String address,
        List<KycDocumentView> documents, UUID verifiedBy, Instant verifiedAt, UUID approvedBy, Instant approvedAt,
        UUID rejectedBy, Instant rejectedAt, String rejectionReason, Instant createdAt, Instant updatedAt) {

    static KycReviewCaseView of(KycCase c, List<KycDocument> documents) {
        return new KycReviewCaseView(c.getId(), c.getUserId(), c.getLevel(), c.getStatus(), c.getAddress(),
                documents.stream().map(KycDocumentView::of).toList(), c.getVerifiedBy(), c.getVerifiedAt(),
                c.getApprovedBy(), c.getApprovedAt(), c.getRejectedBy(), c.getRejectedAt(), c.getRejectionReason(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    @Override
    public String toString() {
        return "KycReviewCaseView[id=" + id + ", userId=" + userId + ", level=" + level + ", status=" + status
                + ", address=" + (address == null ? null : Masking.FULLY_MASKED) + ", documents=" + documents.size()
                + "]";
    }
}
