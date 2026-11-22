package com.trustledger.core.wallet.infrastructure;

import com.trustledger.core.wallet.domain.ProcessedEventRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code INSERT ... ON CONFLICT DO NOTHING}: one statement that both checks and claims, so two concurrent deliveries
 * of the same event can't both pass (the second waits for the first transaction, then inserts nothing). Joins the
 * caller's transaction.
 */
@Repository
class JdbcProcessedEventRepository implements ProcessedEventRepository {

    private final JdbcClient jdbc;

    JdbcProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markProcessed(UUID eventId, String topic, Instant processedAt) {
        return jdbc.sql("""
                        INSERT INTO wallet.processed_events (event_id, topic, processed_at)
                        VALUES (:eventId, :topic, :processedAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("processedAt", Timestamp.from(processedAt))
                .update() == 1;
    }
}
