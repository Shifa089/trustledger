package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full KYC details (FR-KYC-2): opens a FULL case in SUBMITTED. Documents follow through
 * {@link KycDocumentService}; an officer can verify only once the required ones are there.
 */
@Service
public class FullKycService {

    private static final Logger log = LoggerFactory.getLogger(FullKycService.class);

    private final KycCaseRepository cases;
    private final KycDocumentProperties documentProperties;
    private final Clock clock;

    public FullKycService(KycCaseRepository cases, KycDocumentProperties documentProperties, Clock clock) {
        this.cases = cases;
        this.documentProperties = documentProperties;
        this.clock = clock;
    }

    @Transactional
    public FullKycCaseView submit(UUID userId, String address) {
        // Full KYC upgrades an existing minimum-KYC customer; it is not a way around FR-KYC-1's checks.
        if (!cases.existsByUserIdAndLevelAndStatus(userId, KycLevel.MIN, KycStatus.APPROVED)) {
            throw new TrustLedgerException(ErrorCode.KYC_REQUIRED, "Complete minimum KYC before full KYC.");
        }
        if (cases.existsByUserIdAndLevelAndStatusNot(userId, KycLevel.FULL, KycStatus.REJECTED)) {
            throw alreadySubmitted();
        }
        KycCase kycCase = KycCase.submitFull(userId, address, clock.instant());
        try {
            // The partial unique index (user_id, level) WHERE status <> 'REJECTED' decides a race between two
            // concurrent submissions: the loser fails here with a clean 409.
            kycCase = cases.saveAndFlush(kycCase);
        } catch (DataIntegrityViolationException raceLost) {
            throw alreadySubmitted();
        }
        log.info("Full KYC submitted: case {} for user {}", kycCase.getId(), userId);
        return FullKycCaseView.of(kycCase, documentProperties.requiredTypes());
    }

    private static TrustLedgerException alreadySubmitted() {
        return new TrustLedgerException(ErrorCode.KYC_ALREADY_SUBMITTED,
                "Full KYC has already been submitted or approved.");
    }
}
