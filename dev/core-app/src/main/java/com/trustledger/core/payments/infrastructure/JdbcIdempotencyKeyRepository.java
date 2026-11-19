package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.IdempotencyKeyRecord;
import com.trustledger.core.payments.domain.IdempotencyKeyRepository;
import com.trustledger.core.payments.domain.IdempotencyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link IdempotencyKeyRepository} with plain SQL; joins the caller's transaction. */
@Repository
class JdbcIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private final JdbcClient jdbc;

    JdbcIdempotencyKeyRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * {@code ON CONFLICT ... DO UPDATE ... WHERE expired}: a live row is left alone (0 rows), an expired one is
     * reset to a fresh IN_PROGRESS claim (1 row). A concurrent claim of the same key waits for this
     * transaction on the primary key, then sees the row and gets 0.
     */
    @Override
    public boolean claim(UUID userId, UUID key, String requestHash, Instant now, Instant expiresAt) {
        int rows = jdbc.sql("""
                insert into payments.idempotency_keys
                    (user_id, idem_key, request_hash, status, created_at, expires_at)
                values (:userId, :key, :hash, 'IN_PROGRESS', :now, :expiresAt)
                on conflict (user_id, idem_key) do update
                    set request_hash = excluded.request_hash, status = 'IN_PROGRESS', response_status = null,
                        response_body = null, created_at = excluded.created_at, expires_at = excluded.expires_at
                    where payments.idempotency_keys.expires_at <= excluded.created_at""")
                .param("userId", userId).param("key", key).param("hash", requestHash)
                .param("now", utc(now)).param("expiresAt", utc(expiresAt))
                .update();
        return rows == 1;
    }

    @Override
    public Optional<IdempotencyKeyRecord> find(UUID userId, UUID key) {
        return jdbc.sql("""
                select user_id, idem_key, request_hash, status, response_status, response_body::text as body,
                    expires_at
                from payments.idempotency_keys where user_id = :userId and idem_key = :key""")
                .param("userId", userId).param("key", key)
                .query(JdbcIdempotencyKeyRepository::map)
                .optional();
    }

    @Override
    public boolean complete(UUID userId, UUID key, int responseStatus, String responseBody) {
        return jdbc.sql("""
                update payments.idempotency_keys
                set status = 'COMPLETED', response_status = :status, response_body = cast(:body as jsonb)
                where user_id = :userId and idem_key = :key and status = 'IN_PROGRESS'""")
                .param("status", responseStatus).param("body", responseBody, java.sql.Types.VARCHAR)
                .param("userId", userId).param("key", key)
                .update() == 1;
    }

    @Override
    public boolean release(UUID userId, UUID key) {
        return jdbc.sql("""
                delete from payments.idempotency_keys
                where user_id = :userId and idem_key = :key and status = 'IN_PROGRESS'""")
                .param("userId", userId).param("key", key)
                .update() == 1;
    }

    /** {@code SKIP LOCKED}: a row a claim is taking over right now is left for the next run. */
    @Override
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                delete from payments.idempotency_keys k
                using (select user_id, idem_key from payments.idempotency_keys
                       where expires_at <= :now order by expires_at limit :limit
                       for update skip locked) e
                where k.user_id = e.user_id and k.idem_key = e.idem_key""")
                .param("now", utc(now)).param("limit", limit)
                .update();
    }

    private static IdempotencyKeyRecord map(ResultSet rs, int row) throws SQLException {
        int status = rs.getInt("response_status");
        Integer responseStatus = rs.wasNull() ? null : status;
        return new IdempotencyKeyRecord(rs.getObject("user_id", UUID.class), rs.getObject("idem_key", UUID.class),
                rs.getString("request_hash"), IdempotencyStatus.valueOf(rs.getString("status")), responseStatus,
                rs.getString("body"), rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
