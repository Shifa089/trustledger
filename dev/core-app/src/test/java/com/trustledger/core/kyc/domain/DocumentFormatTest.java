package com.trustledger.core.kyc.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** FR-KYC-2: the type is decided by the first bytes only. */
class DocumentFormatTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "255044462d312e370a,            PDF",
            "255044462d,                    PDF",
            "ffd8ffe000104a4649460001,      JPEG",
            "ffd8ffe1,                      JPEG",
            "ffd8ff,                        JPEG",
            "89504e470d0a1a0a0000000d4948,  PNG",
            "89504e470d0a1a0a,              PNG"
    })
    void recognisesEachSignatureAtOffsetZero(String hex, DocumentFormat expected) {
        assertThat(DocumentFormat.detect(HexFormat.of().parseHex(hex))).contains(expected);
    }

    /**
     * A Windows .exe (MZ), GIF89a, ZIP/.docx, ELF, HTML, a PDF signature off by one byte, truncated PDF, JPEG
     * and PNG signatures, %PDF- not at offset 0, and a single zero byte.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"4d5a90000300", "474946383961", "504b0304", "7f454c46", "3c68746d6c3e", "2550444623",
            "25504446", "ffd8", "89504e470d0a1a", "20255044462d", "00"})
    void rejectsEverythingElse(String hex) {
        assertThat(DocumentFormat.detect(HexFormat.of().parseHex(hex))).isEmpty();
    }

    @Test
    void emptyOrNullIsNothing() {
        assertThat(DocumentFormat.detect(new byte[0])).isEmpty();
        assertThat(DocumentFormat.detect(null)).isEmpty();
    }

    @Test
    void textThatMentionsPdfLaterIsNotAPdf() {
        byte[] text = "hello %PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        assertThat(DocumentFormat.detect(text)).isEmpty();
    }

    @Test
    void contentTypesAreTheOnesTheDatabaseAccepts() {
        assertThat(DocumentFormat.PDF.contentType()).isEqualTo("application/pdf");
        assertThat(DocumentFormat.JPEG.contentType()).isEqualTo("image/jpeg");
        assertThat(DocumentFormat.PNG.contentType()).isEqualTo("image/png");
    }
}
