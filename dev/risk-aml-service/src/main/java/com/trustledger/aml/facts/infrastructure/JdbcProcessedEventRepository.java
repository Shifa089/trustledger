package com.trustledger.aml.facts.infrastructure;

import com.trustledger.aml.facts.domain.ProcessedEventRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code INSERT ... ON CONFLICT DO NOTHING}: one statement that both checks and claims, with no race between a
 * "does it exist?" query and the insert. Plain JDBC because JPA has no such insert. It joins the caller's
 * transaction (same DataSource, Spring-managed connection).
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
                        INSERT INTO aml.processed_events (event_id, topic, processed_at)
                        VALUES (:eventId, :topic, :processedAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("processedAt", Timestamp.from(processedAt))
                .update() == 1;
    }
}
