package com.trustledger.core.privacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PrivacyStoragePropertiesTest {

    @Test
    void bucketAndRegionAreRequired() {
        assertThatThrownBy(() -> new PrivacyStorageProperties(" ", "ap-south-1", null, false, null, null, false))
                .hasMessageContaining("bucket");
        assertThatThrownBy(() -> new PrivacyStorageProperties("b", null, null, false, null, null, false))
                .hasMessageContaining("region");
    }

    @Test
    void staticCredentialsComeAsAPair() {
        assertThatThrownBy(() -> new PrivacyStorageProperties("b", "ap-south-1", null, false, "test", null, false))
                .hasMessageContaining("access-key and secret-key");
        assertThat(new PrivacyStorageProperties("b", "ap-south-1", null, false, "test", "test", false)
                .hasStaticCredentials()).isTrue();
        assertThat(new PrivacyStorageProperties("b", "ap-south-1", null, false, null, null, false)
                .hasStaticCredentials()).isFalse();
    }

    @Test
    void toStringNeverPrintsTheSecret() {
        PrivacyStorageProperties properties = new PrivacyStorageProperties("b", "ap-south-1",
                URI.create("http://localhost:4566"), true, "access", "very-secret-value", true);

        assertThat(properties.toString()).doesNotContain("very-secret-value").doesNotContain("access,")
                .contains("staticCredentials=true");
    }
}
