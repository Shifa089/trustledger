package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /kyc/full}: the new case and the document types the customer must upload next. The address is
 * not echoed back.
 */
public record FullKycCaseView(UUID id, KycLevel level, KycStatus status, List<DocumentType> requiredDocuments,
        Instant createdAt) {

    static FullKycCaseView of(KycCase kycCase, List<DocumentType> requiredDocuments) {
        return new FullKycCaseView(kycCase.getId(), kycCase.getLevel(), kycCase.getStatus(), requiredDocuments,
                kycCase.getCreatedAt());
    }
}
