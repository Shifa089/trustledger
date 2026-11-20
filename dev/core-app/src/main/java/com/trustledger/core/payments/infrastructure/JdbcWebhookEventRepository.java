package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.WebhookEvent;
import com.trustledger.core.payments.domain.WebhookEventRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link WebhookEventRepository} with plain SQL (the {@code ON CONFLICT} on a partial unique index has no JPA
 * equivalent); joins the caller's transaction.
 */
@Repository
class JdbcWebhookEventRepository implements WebhookEventRepository {

    private final JdbcClient jdbc;

    JdbcWebhookEventRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The conflict target repeats the index predicate, so Postgres uses the partial index. */
    @Override
    public boolean insertIfAbsent(WebhookEvent accepted) {
        if (!accepted.signatureValid()) {
            throw new IllegalArgumentException("Only accepted events are de-duplicated");
        }
        return insert(accepted, " on conflict (gateway_event_id) where signature_valid do nothing") == 1;
    }

    @Override
    public void insertRejected(WebhookEvent rejected) {
        if (rejected.signatureValid()) {
            throw new IllegalArgumentException("Not a rejected delivery");
        }
        insert(rejected, "");
    }

    @Override
    public Optional<WebhookEvent> findAcceptedForUpdate(String gatewayEventId) {
        return jdbc.sql("""
                select id, gateway_event_id, event_type, payload::text as payload, signature_valid, received_at,
                    processed_at
                from payments.webhook_events
                where gateway_event_id = :eventId and signature_valid
                for update""")
                .param("eventId", gatewayEventId)
                .query(JdbcWebhookEventRepository::map)
                .optional();
    }

    @Override
    public boolean markProcessed(UUID id, Instant processedAt) {
        return jdbc.sql("""
                update payments.webhook_events set processed_at = :at
                where id = :id and signature_valid and processed_at is null""")
                .param("at", utc(processedAt)).param("id", id)
                .update() == 1;
    }

    private int insert(WebhookEvent event, String conflictClause) {
        return jdbc.sql("""
                insert into payments.webhook_events
                    (id, gateway_event_id, event_type, payload, signature_valid, received_at, processed_at)
                values (:id, :eventId, :type, cast(:payload as jsonb), :valid, :receivedAt, null)""" + conflictClause)
                .param("id", event.id())
                .param("eventId", event.gatewayEventId(), Types.VARCHAR)
                .param("type", event.eventType(), Types.VARCHAR)
                .param("payload", event.payload(), Types.VARCHAR)
                .param("valid", event.signatureValid())
                .param("receivedAt", utc(event.receivedAt()))
                .update();
    }

    private static WebhookEvent map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime processed = rs.getObject("processed_at", OffsetDateTime.class);
        return new WebhookEvent(rs.getObject("id", UUID.class), rs.getString("gateway_event_id"),
                rs.getString("event_type"), rs.getString("payload"), rs.getBoolean("signature_valid"),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                processed == null ? null : processed.toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
