package com.trustledger.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventFilterTest {

    private static final Instant T = Instant.parse("2026-11-22T00:00:00Z");

    private static void refused(Runnable build) {
        assertThatThrownBy(build::run).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void everyFilterIsOptional() {
        assertThat(AuditEventFilter.NONE.entityType()).isNull();
        assertThatCode(() -> new AuditEventFilter("KYC_CASE", UUID.randomUUID(), UUID.randomUUID(),
                "KYC_CASE_VERIFY", T, T.plusSeconds(1))).doesNotThrowAnyException();
    }

    @Test
    void codesMustBeUpperCase() {
        refused(() -> new AuditEventFilter("wallet", null, null, null, null, null));
        refused(() -> new AuditEventFilter(null, null, null, "WALLET_FREEZE'; --", null, null));
        refused(() -> new AuditEventFilter("X".repeat(65), null, null, null, null, null));
    }

    @Test
    void fromMustBeBeforeTo() {
        refused(() -> new AuditEventFilter(null, null, null, null, T, T));
        refused(() -> new AuditEventFilter(null, null, null, null, T.plusSeconds(1), T));
        assertThatCode(() -> new AuditEventFilter(null, null, null, null, T, null)).doesNotThrowAnyException();
        assertThatCode(() -> new AuditEventFilter(null, null, null, null, null, T)).doesNotThrowAnyException();
    }
}
