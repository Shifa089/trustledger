package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.PendingFreezeRequests;
import com.trustledger.events.AccountFreezeRequestedV1;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link PendingFreezeRequests} in plain SQL: JPA has no {@code SKIP LOCKED}. Joins the caller's transaction. */
@Repository
class JdbcPendingFreezeRequests implements PendingFreezeRequests {

    private final JdbcClient jdbc;

    JdbcPendingFreezeRequests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Pending> claim(int limit) {
        return jdbc.sql("""
                        SELECT id, requested_at, wallet_id, case_id, rule_code, correlation_id, attempts
                        FROM aml.freeze_requests
                        WHERE published_at IS NULL
                        ORDER BY requested_at, id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, row) -> new Pending(
                        new AccountFreezeRequestedV1(
                                rs.getObject("id", UUID.class),
                                rs.getTimestamp("requested_at").toInstant(),
                                rs.getObject("wallet_id", UUID.class),
                                rs.getObject("case_id", UUID.class),
                                rs.getString("rule_code")),
                        rs.getString("correlation_id"),
                        rs.getInt("attempts")))
                .list();
    }

    @Override
    public void markPublished(UUID id, Instant publishedAt) {
        jdbc.sql("""
                        UPDATE aml.freeze_requests
                        SET published_at = :publishedAt, attempts = attempts + 1
                        WHERE id = :id AND published_at IS NULL
                        """)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(UUID id) {
        jdbc.sql("UPDATE aml.freeze_requests SET attempts = attempts + 1 WHERE id = :id AND published_at IS NULL")
                .param("id", id)
                .update();
    }
}
