package com.trustledger.core.audit.infrastructure;

import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.audit.domain.AuditEventStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code audit.audit_events} with plain SQL: rows are only ever inserted, and {@code before}/{@code after} are
 * written as the application's canonical JSON. Joins the caller's transaction (the JPA transaction manager
 * exposes its connection to {@link JdbcClient}).
 */
@Repository
class JdbcAuditEventStore implements AuditEventStore {

    /**
     * The key of the chain's advisory lock: the ASCII bytes of {@code "TL_AUDIT"}. Advisory lock keys are global to
     * the database, so it is spelled out to stay clear of any other lock the application may take one day.
     */
    static final long CHAIN_LOCK_KEY = 0x544C_5F41_5544_4954L;

    private final JdbcClient jdbc;

    JdbcAuditEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockChain() {
        // pg_advisory_xact_lock returns void; reading the row just executes the statement.
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", CHAIN_LOCK_KEY).query().listOfRows();
    }

    @Override
    public Optional<String> latestHash() {
        return jdbc.sql("SELECT hash FROM audit.audit_events ORDER BY seq DESC LIMIT 1")
                .query(String.class)
                .optional();
    }

    @Override
    public long append(AuditEvent event, String prevHash, String hash, Instant createdAt) {
        return jdbc.sql("""
                        INSERT INTO audit.audit_events (occurred_at, actor_id, actor_role, action, entity_type,
                                entity_id, before, after, ip, correlation_id, prev_hash, hash, created_at)
                        VALUES (:occurredAt, :actorId, :actorRole, :action, :entityType, :entityId,
                                cast(:before as jsonb), cast(:after as jsonb), :ip, :correlationId, :prevHash, :hash,
                                :createdAt)
                        RETURNING seq
                        """)
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("actorId", event.actorId())
                .param("actorRole", event.actorRole())
                .param("action", event.action())
                .param("entityType", event.entityType())
                .param("entityId", event.entityId())
                .param("before", event.beforeJson())
                .param("after", event.afterJson())
                .param("ip", event.ip())
                .param("correlationId", event.correlationId())
                .param("prevHash", prevHash)
                .param("hash", hash)
                .param("createdAt", Timestamp.from(createdAt))
                .query(Long.class)
                .single();
    }
}
