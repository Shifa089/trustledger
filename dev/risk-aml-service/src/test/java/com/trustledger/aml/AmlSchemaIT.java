package com.trustledger.aml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The {@code aml} schema's own safety nets (V1-V3), straight against PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmlSchemaIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-21T08:00:00Z"));

    @Autowired
    JdbcClient jdbc;

    @Test
    void migrationsRunIntoTheirOwnSchemaAndHistory() {
        assertThat(jdbc.sql("SELECT count(*) FROM aml.flyway_schema_history WHERE success").query(Long.class)
                .single()).isGreaterThanOrEqualTo(3L);
        assertThat(jdbc.sql("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'aml' ORDER BY table_name
                """).query(String.class).list())
                .contains("alerts", "cases", "processed_events", "transaction_facts");
    }

    @Test
    void oneEventGivesAtMostOneFactPerDirection() {
        UUID event = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        insertFact(event, sender, receiver, "OUT", "TRANSFER", 100);
        insertFact(event, receiver, sender, "IN", "TRANSFER", 100);

        assertThatThrownBy(() -> insertFact(event, sender, receiver, "OUT", "TRANSFER", 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void factChecksHold() {
        UUID wallet = UUID.randomUUID();
        assertThatThrownBy(() -> insertFact(UUID.randomUUID(), wallet, UUID.randomUUID(), "OUT", "TRANSFER", 0))
                .as("amount must be positive").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFact(UUID.randomUUID(), wallet, null, "OUT", "TRANSFER", 1))
                .as("a transfer names the other wallet").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFact(UUID.randomUUID(), wallet, wallet, "OUT", "TRANSFER", 1))
                .as("not to itself").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFact(UUID.randomUUID(), wallet, null, "OUT", "TOPUP", 1))
                .as("a top-up is always IN").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFact(UUID.randomUUID(), wallet, UUID.randomUUID(), "IN", "TOPUP", 1))
                .as("a top-up has no counterparty wallet").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void factsAreAppendOnly() {
        UUID event = UUID.randomUUID();
        insertFact(event, UUID.randomUUID(), null, "IN", "TOPUP", 100);

        assertThatThrownBy(() -> jdbc.sql("UPDATE aml.transaction_facts SET amount_minor = 1 WHERE event_id = ?")
                .param(event).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM aml.transaction_facts WHERE event_id = ?")
                .param(event).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aWalletHasAtMostOneOpenCase() {
        UUID wallet = UUID.randomUUID();
        insertCase(wallet, "OPEN", null);

        assertThatThrownBy(() -> insertCase(wallet, "INVESTIGATING", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Finished cases don't count: after a close, a new case can open.
        UUID other = UUID.randomUUID();
        insertCase(other, "CLOSED_FALSE_POSITIVE", "Salary payments, verified");
        insertCase(other, "ESCALATED", "STR filed");
        insertCase(other, "OPEN", null);
    }

    @Test
    void caseAndAlertChecksHold() {
        assertThatThrownBy(() -> insertCase(UUID.randomUUID(), "ESCALATED", null))
                .as("a finished case says why").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCase(UUID.randomUUID(), "OPEN", "too early"))
                .as("an open case has no resolution yet").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCase(UUID.randomUUID(), "UNKNOWN", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID wallet = UUID.randomUUID();
        UUID caseId = insertCase(wallet, "OPEN", null);
        insertAlert(caseId, wallet, "R2", "HIGH");
        assertThatThrownBy(() -> insertAlert(caseId, wallet, "R9", "HIGH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAlert(caseId, wallet, "R1", "LOW"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAlert(UUID.randomUUID(), wallet, "R1", "MEDIUM"))
                .as("an alert belongs to an existing case").isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertFact(UUID event, UUID wallet, UUID counterparty, String direction, String source,
            long amount) {
        jdbc.sql("""
                INSERT INTO aml.transaction_facts (id, event_id, wallet_id, counterparty_wallet_id, direction, source,
                    reference_id, amount_minor, currency, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'INR', ?, ?)
                """)
                .params(UUID.randomUUID(), event, wallet, counterparty, direction, source, UUID.randomUUID(), amount,
                        NOW, NOW)
                .update();
    }

    private UUID insertCase(UUID wallet, String status, String resolution) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO aml.cases (id, wallet_id, status, resolution, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(id, wallet, status, resolution, NOW, NOW).update();
        return id;
    }

    private void insertAlert(UUID caseId, UUID wallet, String rule, String severity) {
        jdbc.sql("""
                INSERT INTO aml.alerts (id, case_id, wallet_id, rule_code, severity, details, created_at)
                VALUES (?, ?, ?, ?, ?, 'test', ?)
                """).params(UUID.randomUUID(), caseId, wallet, rule, severity, NOW).update();
    }
}
