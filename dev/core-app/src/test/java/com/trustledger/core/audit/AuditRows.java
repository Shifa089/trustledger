package com.trustledger.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.audit.domain.AuditHashing;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test support: reads {@code audit.audit_events} back with plain SQL and rebuilds each row's {@link AuditEvent}
 * from the stored columns only. That the rebuilt event hashes to the stored hash proves the canonical form survives
 * the {@code jsonb}/{@code timestamptz} round trip, which is what the verifier (Day 20) relies on.
 */
public final class AuditRows {

    public record Row(long seq, AuditEvent event, String prevHash, String hash) {
    }

    private static final String SELECT = """
            select seq, occurred_at, actor_id, actor_role, action, entity_type, entity_id, before::text as before_json,
                   after::text as after_json, ip, user_agent, correlation_id, prev_hash, hash
              from audit.audit_events""";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AuditRows(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Row> forEntity(UUID entityId) {
        return jdbc.query(SELECT + " where entity_id = ? order by seq", this::row, entityId);
    }

    public Row single(UUID entityId, String action) {
        List<Row> rows = jdbc.query(SELECT + " where entity_id = ? and action = ? order by seq", this::row,
                entityId, action);
        assertThat(rows).as("audit rows for %s %s", action, entityId).hasSize(1);
        return rows.getFirst();
    }

    public List<Row> all() {
        return jdbc.query(SELECT + " order by seq", this::row);
    }

    /**
     * The whole chain, in {@code seq} order: starts at genesis, every row links to the one before it, and every
     * stored hash is the hash of the row as stored.
     */
    public void assertChainIntact() {
        String expectedPrev = AuditHashing.GENESIS_HASH;
        for (Row row : all()) {
            assertThat(row.prevHash()).as("prev_hash of seq %d", row.seq()).isEqualTo(expectedPrev);
            assertThat(AuditHashing.hash(row.prevHash(), row.event())).as("hash of seq %d", row.seq())
                    .isEqualTo(row.hash());
            expectedPrev = row.hash();
        }
    }

    private Row row(ResultSet rs, int rowNum) throws SQLException {
        AuditEvent event = new AuditEvent(rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_role"), rs.getString("action"),
                rs.getString("entity_type"), rs.getObject("entity_id", UUID.class), map(rs.getString("before_json")),
                map(rs.getString("after_json")), rs.getString("ip"), rs.getString("correlation_id"),
                rs.getString("user_agent"));
        return new Row(rs.getLong("seq"), event, rs.getString("prev_hash"), rs.getString("hash"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String text) {
        return text == null ? null : json.readValue(text, Map.class);
    }
}
