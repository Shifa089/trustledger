package com.trustledger.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** FR-A-1 / ADR 0008: {@code hash = SHA-256(prevHash + canonical JSON)}. */
class AuditHashingTest {

    static final UUID ACTOR = UUID.fromString("0192f5c0-0000-7000-8000-000000000001");
    static final UUID WALLET = UUID.fromString("0192f5c0-0000-7000-8000-0000000000aa");

    static AuditEvent frozen() {
        return new AuditEvent(Instant.parse("2026-11-22T10:15:30.123456789Z"), ACTOR, "COMPLIANCE_OFFICER",
                "WALLET_FROZEN", "WALLET", WALLET, Map.of("status", "ACTIVE"),
                Map.of("status", "FROZEN", "freezeSource", "COMPLIANCE"), "203.0.113.7", "corr-1");
    }

    @Test
    void canonicalJsonIsPinned() {
        assertThat(frozen().canonicalJson()).isEqualTo("{\"action\":\"WALLET_FROZEN\","
                + "\"actorId\":\"0192f5c0-0000-7000-8000-000000000001\",\"actorRole\":\"COMPLIANCE_OFFICER\","
                + "\"after\":{\"freezeSource\":\"COMPLIANCE\",\"status\":\"FROZEN\"},\"before\":{\"status\":\"ACTIVE\"},"
                + "\"correlationId\":\"corr-1\",\"entityId\":\"0192f5c0-0000-7000-8000-0000000000aa\","
                + "\"entityType\":\"WALLET\",\"ip\":\"203.0.113.7\",\"occurredAt\":\"2026-11-22T10:15:30.123456Z\"}");
    }

    /** Computed outside Java ({@code printf '%s' '<genesis><json>' | sha256sum}), so this is not Java checking Java. */
    @Test
    void matchesAnIndependentlyComputedVector() {
        assertThat(AuditHashing.hash(AuditHashing.GENESIS_HASH, frozen()))
                .isEqualTo("29c34aecb02a3bad53346c99facc229ce67e1855a696d06a8ec80dd383258de8");
    }

    @Test
    void genesisIsSixtyFourZeros() {
        assertThat(AuditHashing.GENESIS_HASH).hasSize(64).matches("0+");
    }

    @Test
    void isDeterministic() {
        assertThat(AuditHashing.hash(AuditHashing.GENESIS_HASH, frozen()))
                .isEqualTo(AuditHashing.hash(AuditHashing.GENESIS_HASH, frozen()))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void dependsOnThePreviousHash() {
        String other = "f".repeat(64);
        assertThat(AuditHashing.hash(other, frozen())).isNotEqualTo(AuditHashing.hash(AuditHashing.GENESIS_HASH,
                frozen()));
    }

    @Test
    void everyFieldIsCovered() {
        String base = AuditHashing.hash(AuditHashing.GENESIS_HASH, frozen());
        AuditEvent e = frozen();
        java.util.List<UnaryOperator<AuditEvent>> changes = java.util.List.of(
                x -> new AuditEvent(x.occurredAt().plusNanos(1000), x.actorId(), x.actorRole(), x.action(),
                        x.entityType(), x.entityId(), x.before(), x.after(), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), UUID.randomUUID(), x.actorRole(), x.action(), x.entityType(),
                        x.entityId(), x.before(), x.after(), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), "ADMIN", x.action(), x.entityType(), x.entityId(),
                        x.before(), x.after(), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), x.actorRole(), "WALLET_UNFROZEN", x.entityType(),
                        x.entityId(), x.before(), x.after(), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), x.actorRole(), x.action(), "USER", x.entityId(),
                        x.before(), x.after(), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), x.actorRole(), x.action(), x.entityType(),
                        UUID.randomUUID(), x.before(), x.after(), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), x.actorRole(), x.action(), x.entityType(),
                        x.entityId(), Map.of("status", "CLOSED"), x.after(), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), x.actorRole(), x.action(), x.entityType(),
                        x.entityId(), x.before(), Map.of("status", "FROZEN"), x.ip(), x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), x.actorRole(), x.action(), x.entityType(),
                        x.entityId(), x.before(), x.after(), "198.51.100.1", x.correlationId()),
                x -> new AuditEvent(x.occurredAt(), x.actorId(), x.actorRole(), x.action(), x.entityType(),
                        x.entityId(), x.before(), x.after(), x.ip(), "corr-2"));

        assertThat(changes).allSatisfy(change ->
                assertThat(AuditHashing.hash(AuditHashing.GENESIS_HASH, change.apply(e))).isNotEqualTo(base));
    }

    @Test
    void refusesAMalformedPreviousHash() {
        assertThatThrownBy(() -> AuditHashing.hash("ABC", frozen())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditHashing.hash("A".repeat(64), frozen()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditHashing.hash(null, frozen())).isInstanceOf(IllegalArgumentException.class);
    }
}
