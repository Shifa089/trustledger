package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.kyc.domain.KycStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of the compliance queue. No personal data: the officer opens the case for the address. */
public record KycQueueItem(UUID id, UUID userId, KycStatus status, List<KycDocumentView> documents,
        UUID verifiedBy, Instant createdAt, Instant updatedAt) {

    static KycQueueItem of(KycCase c, List<KycDocument> documents) {
        return new KycQueueItem(c.getId(), c.getUserId(), c.getStatus(),
                documents.stream().map(KycDocumentView::of).toList(), c.getVerifiedBy(), c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
