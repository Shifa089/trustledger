package com.trustledger.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventTest {

    private static AuditEvent event(Instant at, String action, String ip, Map<String, Object> after) {
        return new AuditEvent(at, null, "SYSTEM", action, "WALLET", UUID.randomUUID(), null, after, ip, null);
    }

    @Test
    void occurredAtIsCutToMicrosecondsLikeTimestamptz() {
        AuditEvent event = event(Instant.parse("2026-11-22T10:15:30.123456999Z"), "WALLET_CREATED", null, null);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-22T10:15:30.123456Z"));
        assertThat(event.canonicalJson()).contains("\"occurredAt\":\"2026-11-22T10:15:30.123456Z\"");
    }

    @Test
    void occurredAtAlwaysHasSixFractionDigits() {
        AuditEvent event = event(Instant.parse("2026-11-22T10:15:30Z"), "WALLET_CREATED", null, null);

        assertThat(event.canonicalJson()).contains("\"occurredAt\":\"2026-11-22T10:15:30.000000Z\"");
    }

    @Test
    void anOverlongIpIsDroppedNotFatal() {
        assertThat(event(Instant.EPOCH, "WALLET_CREATED", "x".repeat(65), null).ip()).isNull();
        assertThat(event(Instant.EPOCH, "WALLET_CREATED", "2001:db8::1", null).ip()).isEqualTo("2001:db8::1");
    }

    @Test
    void actionAndEntityTypeMustBeCodes() {
        assertThatThrownBy(() -> event(Instant.EPOCH, "wallet frozen", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(Instant.EPOCH, null, "SYSTEM", "X", "wallet", UUID.randomUUID(),
                null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedValuesFailBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> event(Instant.EPOCH, "WALLET_CREATED", null, Map.of("amount", 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stateIsCopied() {
        Map<String, Object> after = new HashMap<>();
        after.put("status", "ACTIVE");
        AuditEvent event = event(Instant.EPOCH, "WALLET_CREATED", null, after);
        after.put("status", "CLOSED");

        assertThat(event.after()).containsEntry("status", "ACTIVE");
        assertThat(event.afterJson()).isEqualTo("{\"status\":\"ACTIVE\"}");
        assertThat(event.beforeJson()).isNull();
    }

    @Test
    void ofTakesActorIpAndCorrelationFromTheContext() {
        UUID actor = UUID.randomUUID();
        UUID wallet = UUID.randomUUID();
        AuditEntry entry = new AuditEntry(AuditAction.WALLET_UNFROZEN, AuditEntityType.WALLET, wallet,
                Map.of("status", "FROZEN"), Map.of("status", "ACTIVE"));
        AuditContext context = new AuditContext(new AuditActor(actor, "COMPLIANCE_OFFICER"), "203.0.113.7", "c-1");

        AuditEvent event = AuditEvent.of(Instant.EPOCH, entry, context);

        assertThat(event.actorId()).isEqualTo(actor);
        assertThat(event.actorRole()).isEqualTo("COMPLIANCE_OFFICER");
        assertThat(event.action()).isEqualTo("WALLET_UNFROZEN");
        assertThat(event.entityType()).isEqualTo("WALLET");
        assertThat(event.entityId()).isEqualTo(wallet);
        assertThat(event.ip()).isEqualTo("203.0.113.7");
        assertThat(event.correlationId()).isEqualTo("c-1");
    }
}
