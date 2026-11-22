package com.trustledger.core.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One {@code audit.audit_events} row exactly as read back, before any checks: what the auditor sees and what
 * the verifier recomputes the hash from. {@code before}/{@code after} are the parsed {@code jsonb} values, which
 * after tampering need not even be objects.
 */
public record StoredAuditEvent(long seq, Instant occurredAt, UUID actorId, String actorRole, String action,
        String entityType, UUID entityId, Object before, Object after, String ip, String userAgent,
        String correlationId, String prevHash, String hash) {

    /**
     * The hashed content rebuilt from the stored columns alone (ADR 0008).
     *
     * @throws IllegalArgumentException when the row can't be the content of an audit event (a state that is not an
     *                                  object, a fraction, a missing required column)
     */
    public AuditEvent toEvent() {
        try {
            return new AuditEvent(occurredAt, actorId, actorRole, action, entityType, entityId, state(before),
                    state(after), ip, correlationId, userAgent);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("Audit row " + seq + " lacks a required column", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> state(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException("Audit state keys must be strings");
                }
            }
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Audit state must be a JSON object");
    }
}
