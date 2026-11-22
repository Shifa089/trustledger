package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.CaseEvent;
import com.trustledger.aml.cases.domain.PendingCaseEvents;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PendingCaseEvents} in plain SQL: JPA has no {@code SKIP LOCKED}. Joins the caller's transaction. */
@Repository
class JdbcPendingCaseEvents implements PendingCaseEvents {

    private final JdbcClient jdbc;

    JdbcPendingCaseEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Pending> claim(int limit) {
        return jdbc.sql("""
                        SELECT id, occurred_at, wallet_id, case_id, status, correlation_id, attempts
                        FROM aml.case_events
                        WHERE published_at IS NULL
                        ORDER BY occurred_at, id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, row) -> new Pending(
                        CaseEvent.toV1(
                                rs.getObject("id", UUID.class),
                                rs.getTimestamp("occurred_at").toInstant(),
                                rs.getObject("wallet_id", UUID.class),
                                rs.getObject("case_id", UUID.class),
                                rs.getString("status")),
                        rs.getString("correlation_id"),
                        rs.getInt("attempts")))
                .list();
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.sql("""
                        UPDATE aml.case_events
                        SET published_at = :publishedAt, attempts = attempts + 1
                        WHERE id = :id AND published_at IS NULL
                        """)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(UUID id) {
        jdbc.sql("UPDATE aml.case_events SET attempts = attempts + 1 WHERE id = :id AND published_at IS NULL")
                .param("id", id)
                .update();
    }
}
