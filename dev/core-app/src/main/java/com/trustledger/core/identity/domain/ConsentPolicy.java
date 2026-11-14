package com.trustledger.core.identity.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consent rules (FR-ID-3), pure logic so every rule is unit-tested:
 * <ol>
 * <li>Each purpose at most once per request.</li>
 * <li>The policy version must be the one currently published: nobody can consent to an outdated text.</li>
 * <li>TERMS and PRIVACY cannot be withdrawn ({@code granted=false}); leaving means account erasure (M10).</li>
 * <li>After the request, TERMS and PRIVACY must be granted for the current version, so a first request
 *     must include both, and after a policy update the user must accept the new version.</li>
 * </ol>
 * MARKETING may be granted or withdrawn at any time.
 */
public final class ConsentPolicy {

    private final Map<ConsentPurpose, String> currentVersions;

    /** @param currentVersions the published policy version for every purpose */
    public ConsentPolicy(Map<ConsentPurpose, String> currentVersions) {
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            String version = currentVersions.get(purpose);
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("No current policy version for " + purpose);
            }
        }
        this.currentVersions = Map.copyOf(currentVersions);
    }

    public String currentVersion(ConsentPurpose purpose) {
        return currentVersions.get(purpose);
    }

    public Map<ConsentPurpose, String> currentVersions() {
        return currentVersions;
    }

    /**
     * @param latest  the user's current consent per purpose (latest row), before this request
     * @param choices what the user submits now
     * @throws TrustLedgerException {@code VALIDATION_FAILED} naming the broken rule
     */
    public void check(Map<ConsentPurpose, Consent> latest, List<ConsentChoice> choices) {
        if (choices == null || choices.isEmpty()) {
            throw invalid("At least one consent decision is required.");
        }
        Set<ConsentPurpose> seen = EnumSet.noneOf(ConsentPurpose.class);
        for (ConsentChoice choice : choices) {
            if (!seen.add(choice.purpose())) {
                throw invalid("Each purpose may appear only once (" + choice.purpose() + ").");
            }
            if (!currentVersion(choice.purpose()).equals(choice.policyVersion())) {
                throw invalid("Policy version for " + choice.purpose() + " is not the current one ("
                        + currentVersion(choice.purpose()) + ").");
            }
            if (choice.purpose().required() && !choice.granted()) {
                throw invalid(choice.purpose() + " consent is required and cannot be withdrawn.");
            }
        }
        Map<ConsentPurpose, Boolean> after = new EnumMap<>(ConsentPurpose.class);
        latest.forEach((purpose, consent) -> after.put(purpose, isCurrentGrant(consent)));
        choices.forEach(choice -> after.put(choice.purpose(), choice.granted()));
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            if (purpose.required() && !after.getOrDefault(purpose, false)) {
                throw invalid(purpose + " consent for policy version " + currentVersion(purpose) + " is required.");
            }
        }
    }

    /** True when TERMS and PRIVACY are granted for their current policy versions. */
    public boolean requiredGiven(Collection<Consent> latest) {
        Map<ConsentPurpose, Consent> byPurpose = new EnumMap<>(ConsentPurpose.class);
        latest.forEach(consent -> byPurpose.put(consent.getPurpose(), consent));
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            if (purpose.required()) {
                Consent consent = byPurpose.get(purpose);
                if (consent == null || !isCurrentGrant(consent)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isCurrentGrant(Consent consent) {
        return consent.isGranted() && currentVersion(consent.getPurpose()).equals(consent.getPolicyVersion());
    }

    private static TrustLedgerException invalid(String detail) {
        return new TrustLedgerException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
