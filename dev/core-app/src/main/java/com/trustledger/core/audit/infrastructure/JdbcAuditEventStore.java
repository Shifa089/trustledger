package com.trustledger.core.audit.infrastructure;

import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.audit.domain.AuditEventFilter;
import com.trustledger.core.audit.domain.AuditEventStore;
import com.trustledger.core.audit.domain.StoredAuditEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code audit.audit_events} with plain SQL: rows are only ever inserted, and {@code before}/{@code after} are
 * written as the application's canonical JSON. Joins the caller's transaction (the JPA transaction manager
 * exposes its connection to {@link JdbcClient}).
 * <p>
 * Reading back, {@code before}/{@code after} are parsed into plain maps, lists, strings, integers and booleans:
 * the same values the canonical JSON was written from, so the verifier can recompute the hash.
 */
@Repository
class JdbcAuditEventStore implements AuditEventStore {

    /**
     * The key of the chain's advisory lock: the ASCII bytes of {@code "TL_AUDIT"}. Advisory lock keys are global to
     * the database, so it is spelled out to stay clear of any other lock the application may take one day.
     */
    static final long CHAIN_LOCK_KEY = 0x544C_5F41_5544_4954L;

    private static final String SELECT = """
            SELECT seq, occurred_at, actor_id, actor_role, action, entity_type, entity_id,
                   before::text AS before_json, after::text AS after_json, ip, user_agent, correlation_id,
                   prev_hash, hash
              FROM audit.audit_events
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcAuditEventStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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
                                entity_id, before, after, ip, user_agent, correlation_id, prev_hash, hash, created_at)
                        VALUES (:occurredAt, :actorId, :actorRole, :action, :entityType, :entityId,
                                cast(:before as jsonb), cast(:after as jsonb), :ip, :userAgent, :correlationId,
                                :prevHash, :hash, :createdAt)
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
                .param("userAgent", event.userAgent())
                .param("correlationId", event.correlationId())
                .param("prevHash", prevHash)
                .param("hash", hash)
                .param("createdAt", Timestamp.from(createdAt))
                .query(Long.class)
                .single();
    }

    @Override
    public List<StoredAuditEvent> search(AuditEventFilter filter, long offset, int limit) {
        Where where = where(filter);
        return jdbc.sql(SELECT + where.sql() + " ORDER BY seq DESC LIMIT :limit OFFSET :offset")
                .params(where.params())
                .param("limit", limit)
                .param("offset", offset)
                .query(this::row)
                .list();
    }

    @Override
    public long count(AuditEventFilter filter) {
        Where where = where(filter);
        return jdbc.sql("SELECT count(*) FROM audit.audit_events" + where.sql())
                .params(where.params())
                .query(Long.class)
                .single();
    }

    @Override
    public List<StoredAuditEvent> chainAfter(long afterSeq, int limit) {
        return jdbc.sql(SELECT + " WHERE seq > :afterSeq ORDER BY seq LIMIT :limit")
                .param("afterSeq", afterSeq)
                .param("limit", limit)
                .query(this::row)
                .list();
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    /** Only fixed SQL fragments are concatenated; every value is a bind parameter. */
    private static Where where(AuditEventFilter filter) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (filter.entityType() != null) {
            conditions.add("entity_type = :entityType");
            params.put("entityType", filter.entityType());
        }
        if (filter.entityId() != null) {
            conditions.add("entity_id = :entityId");
            params.put("entityId", filter.entityId());
        }
        if (filter.actorId() != null) {
            conditions.add("actor_id = :actorId");
            params.put("actorId", filter.actorId());
        }
        if (filter.action() != null) {
            conditions.add("action = :action");
            params.put("action", filter.action());
        }
        if (filter.from() != null) {
            conditions.add("occurred_at >= :from");
            params.put("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            conditions.add("occurred_at < :to");
            params.put("to", Timestamp.from(filter.to()));
        }
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    private StoredAuditEvent row(ResultSet rs, int rowNum) throws SQLException {
        return new StoredAuditEvent(rs.getLong("seq"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_role"), rs.getString("action"),
                rs.getString("entity_type"), rs.getObject("entity_id", UUID.class), parse(rs.getString("before_json")),
                parse(rs.getString("after_json")), rs.getString("ip"), rs.getString("user_agent"),
                rs.getString("correlation_id"), rs.getString("prev_hash"), rs.getString("hash"));
    }

    /** Any JSON value (jsonb always holds valid JSON); whether it is a valid audit state is the verifier's call. */
    private Object parse(String text) {
        return text == null ? null : json.readValue(text, Object.class);
    }
}
