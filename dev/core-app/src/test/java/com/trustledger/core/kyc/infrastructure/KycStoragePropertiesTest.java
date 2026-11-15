package com.trustledger.core.kyc.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class KycStoragePropertiesTest {

    @Test
    void awsDefaultsUseTheCredentialChain() {
        KycStorageProperties p = new KycStorageProperties("bucket", "ap-south-1", null, false, null, null, false);
        assertThat(p.hasStaticCredentials()).isFalse();
    }

    @Test
    void bucketAndRegionAreRequired() {
        assertThatThrownBy(() -> new KycStorageProperties(" ", "ap-south-1", null, false, null, null, false))
                .hasMessageContaining("bucket");
        assertThatThrownBy(() -> new KycStorageProperties("b", null, null, false, null, null, false))
                .hasMessageContaining("region");
    }

    @Test
    void staticCredentialsComeInPairs() {
        assertThatThrownBy(() -> new KycStorageProperties("b", "r", null, true, "test", "", false))
                .hasMessageContaining("together");
    }

    @Test
    void toStringNeverShowsTheCredentials() {
        KycStorageProperties p = new KycStorageProperties("b", "r", URI.create("http://localhost:4566"), true,
                "AKIAEXAMPLE", "super-secret-value", true);
        assertThat(p.toString()).doesNotContain("super-secret-value", "AKIAEXAMPLE")
                .contains("staticCredentials=true");
    }
}
