package com.trustledger.core.kyc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** When a case accepts documents (only FULL + SUBMITTED), and ownership. */
class KycCaseDocumentsTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void submittedFullCaseAcceptsDocuments() {
        assertThatCode(() -> full().requireAcceptsDocuments()).doesNotThrowAnyException();
    }

    @Test
    void verifiedCaseNoLongerAcceptsDocuments() {
        KycCase c = full();
        c.verify(UUID.randomUUID(), NOW);
        assertInvalid(c);
    }

    @Test
    void approvedCaseNoLongerAcceptsDocuments() {
        KycCase c = full();
        c.verify(UUID.randomUUID(), NOW);
        c.approve(UUID.randomUUID(), NOW);
        assertInvalid(c);
    }

    @Test
    void rejectedCaseNoLongerAcceptsDocuments() {
        KycCase c = full();
        c.reject(UUID.randomUUID(), "Blurred", NOW);
        assertInvalid(c);
    }

    @Test
    void minCaseNeverAcceptsDocuments() {
        KycCase c = KycCase.submitMin(USER, "Test Person", "ABCPE1234F", "h".repeat(64), LocalDate.of(1990, 1, 1),
                NOW);
        assertInvalid(c);
    }

    @Test
    void ownership() {
        assertThat(full().isOwnedBy(USER)).isTrue();
        assertThat(full().isOwnedBy(UUID.randomUUID())).isFalse();
    }

    private static KycCase full() {
        return KycCase.submitFull(USER, "12 Test Street, Bengaluru", NOW);
    }

    private static void assertInvalid(KycCase c) {
        assertThatThrownBy(c::requireAcceptsDocuments)
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }
}
