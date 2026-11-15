package com.trustledger.core.kyc.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.kyc.domain.AgePolicy;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.crypto.BlindIndex;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minimum KYC (FR-KYC-1, FR-KYC-4). Formats are checked by Bean Validation at the edge and again by the
 * aggregate; this service adds the rules that need state or time: one MIN case per user, 18+ in India,
 * one account per mobile number. Then the case is approved automatically (the OTP check is simulated)
 * and {@link KycLevelUpgraded} is published.
 * <p>
 * Everything runs in one transaction: if any step fails, neither the phone on the user nor the case is
 * stored, and the event is not delivered.
 */
@Service
public class MinKycService {

    /** Ages are counted in India, where the customer lives, not in UTC. */
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private static final Logger log = LoggerFactory.getLogger(MinKycService.class);

    private final KycCaseRepository cases;
    private final IdentityApi identity;
    private final BlindIndex blindIndex;
    private final ApplicationEventPublisher events;
    private final KycProperties properties;
    private final Clock clock;

    public MinKycService(KycCaseRepository cases, IdentityApi identity, BlindIndex blindIndex,
            ApplicationEventPublisher events, KycProperties properties, Clock clock) {
        this.cases = cases;
        this.identity = identity;
        this.blindIndex = blindIndex;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public KycCaseView submit(MinKycCommand command) {
        if (cases.existsByUserIdAndLevelAndStatusNot(command.userId(), KycLevel.MIN, KycStatus.REJECTED)) {
            throw alreadySubmitted();
        }
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, INDIA);
        if (command.dateOfBirth().isAfter(today)) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "Date of birth cannot be in the future.");
        }
        if (!AgePolicy.isAtLeast(properties.minAgeYears(), command.dateOfBirth(), today)) {
            throw new TrustLedgerException(ErrorCode.AGE_REQUIREMENT_NOT_MET,
                    "You must be at least " + properties.minAgeYears() + " years old to complete KYC.");
        }

        KycCase kycCase = KycCase.submitMin(command.userId(), command.fullName(), command.pan(),
                blindIndex.value("pan", command.pan()), command.dateOfBirth(), now);
        kycCase.autoApproveMin(now);
        try {
            // Insert first: the partial unique index (user_id, level) serialises two concurrent requests of
            // the same user. The second insert waits for the first to commit, then fails here, before it
            // can touch the users row (which would end in an optimistic-lock error instead of a clean 409).
            kycCase = cases.saveAndFlush(kycCase);
        } catch (DataIntegrityViolationException raceLost) {
            throw alreadySubmitted();
        }

        // Same transaction: if the number belongs to someone else, the case insert above is rolled back.
        identity.recordVerifiedPhone(command.userId(), command.mobile());

        events.publishEvent(new KycLevelUpgraded(command.userId(), KycLevel.MIN));
        log.info("Minimum KYC approved: case {} for user {}", kycCase.getId(), command.userId());
        return KycCaseView.of(kycCase);
    }

    private static TrustLedgerException alreadySubmitted() {
        return new TrustLedgerException(ErrorCode.KYC_ALREADY_SUBMITTED, "Minimum KYC has already been completed.");
    }
}
