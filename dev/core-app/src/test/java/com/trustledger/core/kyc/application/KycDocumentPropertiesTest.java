package com.trustledger.core.kyc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.kyc.domain.DocumentType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class KycDocumentPropertiesTest {

    private static final List<DocumentType> REQUIRED = List.of(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF);
    private static final DataSize FIVE_MB = DataSize.ofMegabytes(5);
    private static final Duration FIVE_MIN = Duration.ofMinutes(5);

    @Test
    void acceptsTheDefaults() {
        KycDocumentProperties p = new KycDocumentProperties(FIVE_MB, 10, REQUIRED, FIVE_MIN);
        assertThat(p.maxSize().toBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(p.requiredTypeSet()).containsExactlyInAnyOrder(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF);
    }

    @Test
    void maxSizeAboveFiveMegabytesFailsStartup() {
        assertThatThrownBy(() -> new KycDocumentProperties(DataSize.ofBytes(5L * 1024 * 1024 + 1), 10, REQUIRED,
                FIVE_MIN)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-size");
    }

    @Test
    void viewLinkLongerThanFiveMinutesOrZeroFailsStartup() {
        assertThatThrownBy(() -> new KycDocumentProperties(FIVE_MB, 10, REQUIRED, FIVE_MIN.plusSeconds(1)))
                .hasMessageContaining("view-link-ttl");
        assertThatThrownBy(() -> new KycDocumentProperties(FIVE_MB, 10, REQUIRED, Duration.ZERO))
                .hasMessageContaining("view-link-ttl");
    }

    @Test
    void requiredTypesMustNotBeEmptyAndAreDeduplicated() {
        assertThatThrownBy(() -> new KycDocumentProperties(FIVE_MB, 10, List.of(), FIVE_MIN))
                .hasMessageContaining("required-types");
        KycDocumentProperties p = new KycDocumentProperties(FIVE_MB, 10,
                List.of(DocumentType.PHOTO, DocumentType.PAN_CARD, DocumentType.PHOTO), FIVE_MIN);
        assertThat(p.requiredTypes()).containsExactly(DocumentType.PAN_CARD, DocumentType.PHOTO);
    }

    @Test
    void maxPerCaseMustBeSensible() {
        assertThatThrownBy(() -> new KycDocumentProperties(FIVE_MB, 0, REQUIRED, FIVE_MIN))
                .hasMessageContaining("max-per-case");
        assertThatThrownBy(() -> new KycDocumentProperties(FIVE_MB, 51, REQUIRED, FIVE_MIN))
                .hasMessageContaining("max-per-case");
    }
}
