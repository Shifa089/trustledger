package com.trustledger.core.kyc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user's KYC status without personal data or documents (see {@link KycApi#statusOf}).
 *
 * @param currentLevel the highest approved level: {@code NONE}, {@code MIN} or {@code FULL}
 * @param cases        every case of the user, oldest first
 */
public record KycStatusSummary(String currentLevel, List<Case> cases) {

    public static final String NONE = "NONE";

    public KycStatusSummary {
        cases = List.copyOf(cases);
    }

    /**
     * One case: status and when each decision was made. Who decided (officer IDs) is left out: that is staff data,
     * not the customer's. {@code rejectionReason} is the text the customer already sees on {@code GET /kyc/me}.
     */
    public record Case(UUID caseId, KycLevel level, String status, Instant submittedAt, Instant verifiedAt,
            Instant approvedAt, Instant rejectedAt, String rejectionReason) {
    }
}
