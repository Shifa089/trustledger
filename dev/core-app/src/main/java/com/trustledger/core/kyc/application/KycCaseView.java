package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.masking.Masking;
import java.time.Instant;
import java.util.UUID;

/** A KYC case as the customer sees it: status and the masked PAN only (FR-PR-1). */
public record KycCaseView(UUID id, KycLevel level, KycStatus status, String maskedPan, String rejectionReason,
        Instant createdAt, Instant updatedAt) {

    static KycCaseView of(KycCase kycCase) {
        return new KycCaseView(kycCase.getId(), kycCase.getLevel(), kycCase.getStatus(), Masking.pan(kycCase.getPan()),
                kycCase.getRejectionReason(), kycCase.getCreatedAt(), kycCase.getUpdatedAt());
    }
}
