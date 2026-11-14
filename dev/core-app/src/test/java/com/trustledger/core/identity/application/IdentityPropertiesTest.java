package com.trustledger.core.identity.application;

import static com.trustledger.core.identity.domain.ConsentPurpose.MARKETING;
import static com.trustledger.core.identity.domain.ConsentPurpose.PRIVACY;
import static com.trustledger.core.identity.domain.ConsentPurpose.TERMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityPropertiesTest {

    @Test
    void acceptsAVersionForEveryPurpose() {
        IdentityProperties properties = new IdentityProperties(Map.of(TERMS, "t1", PRIVACY, "p1", MARKETING, "m1"));

        assertThat(properties.policyVersions()).containsEntry(TERMS, "t1").containsEntry(MARKETING, "m1");
    }

    @Test
    void missingPurposeFailsStartup() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdentityProperties(Map.of(TERMS, "t1", PRIVACY, "p1")))
                .withMessageContaining("policy-versions.MARKETING");
    }

    @Test
    void nothingConfiguredFailsStartup() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IdentityProperties(null));
    }

    @Test
    void blankOrOverlongVersionFailsStartup() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdentityProperties(Map.of(TERMS, " ", PRIVACY, "p1", MARKETING, "m1")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdentityProperties(Map.of(TERMS, "x".repeat(33), PRIVACY, "p1", MARKETING, "m1")));
    }
}
