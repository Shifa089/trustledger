package com.trustledger.core.identity.domain;

import static com.trustledger.core.identity.domain.ConsentPurpose.MARKETING;
import static com.trustledger.core.identity.domain.ConsentPurpose.PRIVACY;
import static com.trustledger.core.identity.domain.ConsentPurpose.TERMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsentPolicyTest {

    private static final String V1 = "2026-11-01";
    private static final String V2 = "2027-01-01";
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final ConsentPolicy policy = new ConsentPolicy(Map.of(TERMS, V1, PRIVACY, V1, MARKETING, V1));

    @Test
    void firstConsentWithTermsAndPrivacyIsAccepted() {
        assertThatCode(() -> policy.check(Map.of(), List.of(grant(TERMS), grant(PRIVACY)))).doesNotThrowAnyException();
    }

    @Test
    void firstConsentMayAlsoGrantMarketing() {
        assertThatCode(() -> policy.check(Map.of(), List.of(grant(TERMS), grant(PRIVACY), grant(MARKETING))))
                .doesNotThrowAnyException();
    }

    @Test
    void firstConsentWithoutPrivacyIsRejected() {
        assertRejected(Map.of(), List.of(grant(TERMS)), "PRIVACY consent for policy version " + V1 + " is required");
    }

    @Test
    void marketingOnlyBeforeRequiredConsentsIsRejected() {
        assertRejected(Map.of(), List.of(grant(MARKETING)), "TERMS consent");
    }

    @Test
    void termsCannotBeWithdrawn() {
        assertRejected(given(TERMS, PRIVACY), List.of(new ConsentChoice(TERMS, V1, false)),
                "TERMS consent is required and cannot be withdrawn");
    }

    @Test
    void privacyCannotBeWithdrawn() {
        assertRejected(given(TERMS, PRIVACY), List.of(new ConsentChoice(PRIVACY, V1, false)),
                "PRIVACY consent is required and cannot be withdrawn");
    }

    @Test
    void marketingCanBeGrantedAndWithdrawnLater() {
        Map<ConsentPurpose, Consent> latest = given(TERMS, PRIVACY);

        assertThatCode(() -> policy.check(latest, List.of(grant(MARKETING)))).doesNotThrowAnyException();
        assertThatCode(() -> policy.check(latest, List.of(new ConsentChoice(MARKETING, V1, false))))
                .doesNotThrowAnyException();
    }

    @Test
    void outdatedPolicyVersionIsRejected() {
        assertRejected(Map.of(), List.of(new ConsentChoice(TERMS, "2020-01-01", true), grant(PRIVACY)),
                "Policy version for TERMS is not the current one (" + V1 + ")");
    }

    @Test
    void duplicatePurposeIsRejected() {
        assertRejected(Map.of(), List.of(grant(TERMS), grant(PRIVACY), grant(TERMS)),
                "Each purpose may appear only once (TERMS)");
    }

    @Test
    void emptyRequestIsRejected() {
        assertRejected(Map.of(), List.of(), "At least one consent decision is required");
    }

    @Test
    void afterAPolicyUpdateTheNewVersionMustBeAccepted() {
        ConsentPolicy updated = new ConsentPolicy(Map.of(TERMS, V2, PRIVACY, V1, MARKETING, V1));
        Map<ConsentPurpose, Consent> latest = given(TERMS, PRIVACY); // TERMS granted for V1 only

        assertThatThrownBy(() -> updated.check(latest, List.of(grant(MARKETING))))
                .hasMessageContaining("TERMS consent for policy version " + V2 + " is required");
        assertThatCode(() -> updated.check(latest, List.of(new ConsentChoice(TERMS, V2, true))))
                .doesNotThrowAnyException();
    }

    @Test
    void requiredGivenNeedsTermsAndPrivacyForCurrentVersions() {
        assertThat(policy.requiredGiven(List.of())).isFalse();
        assertThat(policy.requiredGiven(List.of(consent(TERMS, V1, true)))).isFalse();
        assertThat(policy.requiredGiven(List.of(consent(TERMS, V1, true), consent(PRIVACY, V1, true)))).isTrue();
        assertThat(policy.requiredGiven(List.of(consent(TERMS, V1, true), consent(PRIVACY, "old", true)))).isFalse();
        assertThat(policy.requiredGiven(List.of(consent(TERMS, V1, true), consent(PRIVACY, V1, true),
                consent(MARKETING, V1, false)))).isTrue();
    }

    @Test
    void everyPurposeNeedsACurrentVersion() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConsentPolicy(Map.of(TERMS, V1, PRIVACY, V1)))
                .withMessageContaining("MARKETING");
    }

    private void assertRejected(Map<ConsentPurpose, Consent> latest, List<ConsentChoice> choices, String detail) {
        assertThatThrownBy(() -> policy.check(latest, choices))
                .isInstanceOf(TrustLedgerException.class)
                .hasMessageContaining(detail)
                .extracting(e -> ((TrustLedgerException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static ConsentChoice grant(ConsentPurpose purpose) {
        return new ConsentChoice(purpose, V1, true);
    }

    private static Map<ConsentPurpose, Consent> given(ConsentPurpose... purposes) {
        Map<ConsentPurpose, Consent> latest = new EnumMap<>(ConsentPurpose.class);
        for (ConsentPurpose purpose : purposes) {
            latest.put(purpose, consent(purpose, V1, true));
        }
        return latest;
    }

    private static Consent consent(ConsentPurpose purpose, String version, boolean granted) {
        return Consent.record(USER, new ConsentChoice(purpose, version, granted), "127.0.0.1", NOW);
    }
}
