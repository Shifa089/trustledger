package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V14: the database refuses an inconsistent webhook row even for raw SQL, and de-duplicates by gateway event ID
 * among accepted events only (the partial unique index).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WebhookEventsMigrationIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-20T10:00:00Z"));
    private static final String PAYLOAD = "{\"event\":\"payment.captured\"}";

    @Autowired
    JdbcTemplate jdbc;

    private static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void insert(String eventId, String type, String payload, boolean valid, Timestamp processedAt) {
        jdbc.update("""
                insert into payments.webhook_events
                    (id, gateway_event_id, event_type, payload, signature_valid, received_at, processed_at)
                values (?, ?, ?, cast(? as jsonb), ?, ?, ?)""",
                UUID.randomUUID(), eventId, type, payload, valid, NOW, processedAt);
    }

    private static void refusedBy(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void everyConsistentRowIsAccepted() {
        insert(newEventId(), "payment.captured", PAYLOAD, true, null);
        insert(newEventId(), "payment.captured", PAYLOAD, true, NOW);
        insert(null, null, null, false, null);
        insert(newEventId(), "payment.captured", PAYLOAD, false, null);
    }

    @Test
    void acceptedRowNeedsEventIdTypeAndPayload() {
        refusedBy(() -> insert(null, "payment.captured", PAYLOAD, true, null), "ck_webhook_events_valid_complete");
        refusedBy(() -> insert(newEventId(), null, PAYLOAD, true, null), "ck_webhook_events_valid_complete");
        refusedBy(() -> insert(newEventId(), "payment.captured", null, true, null),
                "ck_webhook_events_valid_complete");
    }

    @Test
    void rejectedRowIsNeverProcessed() {
        refusedBy(() -> insert(newEventId(), "payment.captured", PAYLOAD, false, NOW),
                "ck_webhook_events_processed_valid");
    }

    @Test
    void twoAcceptedRowsWithTheSameEventIdAreRefused() {
        String eventId = newEventId();
        insert(eventId, "payment.captured", PAYLOAD, true, null);
        assertThatThrownBy(() -> insert(eventId, "payment.captured", PAYLOAD, true, null))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_webhook_events_gateway_event");
    }

    /** The security reason for the partial index: a forged delivery can't take a genuine event's ID. */
    @Test
    void rejectedRowsDoNotTakePartInDeDuplication() {
        String eventId = newEventId();
        insert(eventId, "payment.captured", PAYLOAD, false, null);
        insert(eventId, "payment.captured", PAYLOAD, false, null);
        insert(eventId, "payment.captured", PAYLOAD, true, null);

        assertThat(jdbc.queryForObject(
                "select count(*) from payments.webhook_events where gateway_event_id = ?", Long.class, eventId))
                .isEqualTo(3);
    }

    @Test
    void payloadIsJsonb() {
        assertThat(jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_schema = 'payments' and table_name = 'webhook_events' and column_name = 'payload'""",
                String.class)).isEqualTo("jsonb");
    }
}
