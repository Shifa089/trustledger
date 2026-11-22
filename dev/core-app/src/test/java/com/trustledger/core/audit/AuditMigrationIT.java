package com.trustledger.core.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.audit.application.AuditWriter;
import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.audit.domain.ChainedAuditEvent;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V19: the database safety nets on their own, with raw SQL that bypasses the Java code. Every raw insert here is
 * refused, so the shared chain stays valid for the other tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditMigrationIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    AuditWriter writer;

    private static void refused(ThrowingCallable action, String text) {
        assertThatThrownBy(action).rootCause().hasMessageContaining(text);
    }

    private ChainedAuditEvent written() {
        return tx.execute(status -> writer.write(new AuditEntry(AuditAction.WALLET_CREATED, AuditEntityType.WALLET,
                UUID.randomUUID(), null, Map.of("status", "ACTIVE")), AuditContext.SYSTEM));
    }

    private void insert(String actorRole, UUID actorId, String prevHash, String hash) {
        jdbc.update("""
                insert into audit.audit_events (occurred_at, actor_id, actor_role, action, entity_type, entity_id,
                        prev_hash, hash, created_at)
                values (now(), ?, ?, 'WALLET_CREATED', 'WALLET', ?, ?, ?, now())""",
                actorId, actorRole, UUID.randomUUID(), prevHash, hash);
    }

    @Test
    void updateIsRefused() {
        ChainedAuditEvent row = written();

        refused(() -> jdbc.update("update audit.audit_events set actor_role = 'ADMIN' where seq = ?", row.seq()),
                "append-only");
        refused(() -> jdbc.update("update audit.audit_events set after = '{}'::jsonb where seq = ?", row.seq()),
                "append-only");
    }

    @Test
    void deleteIsRefused() {
        ChainedAuditEvent row = written();

        refused(() -> jdbc.update("delete from audit.audit_events where seq = ?", row.seq()), "append-only");
    }

    @Test
    void truncateIsRefused() {
        written();

        refused(() -> jdbc.execute("truncate audit.audit_events"), "append-only");
    }

    /** Two rows extending the same predecessor would fork the chain. */
    @Test
    void aForkIsRefused() {
        ChainedAuditEvent row = written();

        refused(() -> insert("SYSTEM", null, row.prevHash(), "e".repeat(64)), "uk_audit_events_prev_hash");
    }

    @Test
    void aDuplicateHashIsRefused() {
        ChainedAuditEvent row = written();

        refused(() -> insert("SYSTEM", null, "d".repeat(64), row.hash()), "uk_audit_events_hash");
    }

    @Test
    void hashesMustBeLowerCaseHex() {
        refused(() -> insert("SYSTEM", null, "A".repeat(64), "b".repeat(64)), "ck_audit_events_prev_hash");
        refused(() -> insert("SYSTEM", null, "c".repeat(64), "not-a-hash".repeat(6) + "abcd"),
                "ck_audit_events_hash");
    }

    @Test
    void roleMustBeKnownAndOnlyTheSystemAnonymous() {
        refused(() -> insert("ROOT", UUID.randomUUID(), "1".repeat(64), "2".repeat(64)),
                "ck_audit_events_actor_role");
        refused(() -> insert("CUSTOMER", null, "3".repeat(64), "4".repeat(64)), "ck_audit_events_actor");
    }
}
