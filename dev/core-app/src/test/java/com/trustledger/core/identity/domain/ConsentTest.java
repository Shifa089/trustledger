package com.trustledger.core.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsentTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    @Test
    void recordsTheDecisionWithTimeAndIp() {
        Consent consent = Consent.record(USER, new ConsentChoice(ConsentPurpose.MARKETING, "v1", false),
                "2001:db8::1", NOW);

        assertThat(consent.getUserId()).isEqualTo(USER);
        assertThat(consent.getPurpose()).isEqualTo(ConsentPurpose.MARKETING);
        assertThat(consent.getPolicyVersion()).isEqualTo("v1");
        assertThat(consent.isGranted()).isFalse();
        assertThat(consent.getCapturedAt()).isEqualTo(NOW);
        assertThat(consent.getCreatedAt()).isEqualTo(NOW);
        assertThat(consent.getIp()).isEqualTo("2001:db8::1");
    }

    @Test
    void anOverlongIpIsNotStored() {
        Consent consent = Consent.record(USER, new ConsentChoice(ConsentPurpose.TERMS, "v1", true),
                "x".repeat(46), NOW);

        assertThat(consent.getIp()).isNull();
    }

    @Test
    void onlyTermsAndPrivacyAreRequired() {
        assertThat(ConsentPurpose.TERMS.required()).isTrue();
        assertThat(ConsentPurpose.PRIVACY.required()).isTrue();
        assertThat(ConsentPurpose.MARKETING.required()).isFalse();
    }
}
