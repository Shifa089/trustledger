package com.trustledger.core.kyc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KycDocumentTest {

    private static final UUID CASE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final String SHA = "a".repeat(64);

    @Test
    void recordsMetadataWithTheDetectedContentType() {
        KycDocument d = KycDocument.record(CASE, DocumentType.PAN_CARD, "kyc/x/y", DocumentFormat.PNG, 42, SHA,
                USER, NOW);

        assertThat(d.getCaseId()).isEqualTo(CASE);
        assertThat(d.getType()).isEqualTo(DocumentType.PAN_CARD);
        assertThat(d.getS3Key()).isEqualTo("kyc/x/y");
        assertThat(d.getContentType()).isEqualTo("image/png");
        assertThat(d.getSizeBytes()).isEqualTo(42);
        assertThat(d.getSha256()).isEqualTo(SHA);
        assertThat(d.getUploadedBy()).isEqualTo(USER);
        assertThat(d.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void exactlyFiveMegabytesIsAllowedOneMoreIsNotAndEmptyIsNot() {
        KycDocument.record(CASE, DocumentType.PHOTO, "k", DocumentFormat.PDF, KycDocument.MAX_SIZE_BYTES, SHA,
                USER, NOW);

        assertThatThrownBy(() -> KycDocument.record(CASE, DocumentType.PHOTO, "k", DocumentFormat.PDF,
                KycDocument.MAX_SIZE_BYTES + 1, SHA, USER, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KycDocument.record(CASE, DocumentType.PHOTO, "k", DocumentFormat.PDF, 0, SHA,
                USER, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void shaMustBe64LowerCaseHex(String sha) {
        assertThatThrownBy(() -> KycDocument.record(CASE, DocumentType.PHOTO, "k", DocumentFormat.PDF, 1, sha,
                USER, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyIsRequired() {
        assertThatThrownBy(() -> KycDocument.record(CASE, DocumentType.PHOTO, " ", DocumentFormat.PDF, 1, SHA,
                USER, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
