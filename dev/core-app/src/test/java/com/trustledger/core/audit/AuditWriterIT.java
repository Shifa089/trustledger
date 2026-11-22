package com.trustledger.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.audit.application.AuditWriter;
import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditActor;
import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.audit.domain.ChainedAuditEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The writer against Postgres: linking, transaction rules, the stored round trip and concurrent writers. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditWriterIT {

    @Autowired
    AuditWriter writer;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    AuditRows rows;

    @BeforeEach
    void setUp() {
        rows = new AuditRows(jdbc, json);
    }

    private static AuditEntry entry(UUID entityId) {
        return new AuditEntry(AuditAction.WALLET_FROZEN, AuditEntityType.WALLET, entityId,
                Map.of("status", "ACTIVE"), Map.of("status", "FROZEN", "freezeSource", "COMPLIANCE",
                        "note", "quote \" backslash \\ ₹ नमस्ते", "count", 3L));
    }

    private ChainedAuditEvent writeInTx(UUID entityId) {
        return tx.execute(status -> writer.write(entry(entityId), AuditContext.SYSTEM));
    }

    @Test
    void eachEventLinksToThePreviousOne() {
        ChainedAuditEvent first = writeInTx(UUID.randomUUID());
        ChainedAuditEvent second = writeInTx(UUID.randomUUID());

        assertThat(second.seq()).isGreaterThan(first.seq());
        assertThat(second.prevHash()).isEqualTo(first.hash());
    }

    @Test
    void twoEventsInOneTransactionChainToo() {
        List<ChainedAuditEvent> written = tx.execute(status -> List.of(
                writer.write(entry(UUID.randomUUID()), AuditContext.SYSTEM),
                writer.write(entry(UUID.randomUUID()), AuditContext.SYSTEM)));

        assertThat(written.get(1).prevHash()).isEqualTo(written.get(0).hash());
    }

    @Test
    void storedRowsHashToTheirStoredHash() {
        UUID entity = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        ChainedAuditEvent written = tx.execute(status -> writer.write(entry(entity),
                new AuditContext(new AuditActor(officer, "COMPLIANCE_OFFICER"), "2001:db8::7", "corr-it")));

        AuditRows.Row stored = rows.single(entity, "WALLET_FROZEN");

        assertThat(stored.seq()).isEqualTo(written.seq());
        // Not record equality: jsonb hands 3L back as an Integer. What matters is the hashed form.
        assertThat(stored.event().canonicalJson()).isEqualTo(written.event().canonicalJson());
        assertThat(stored.prevHash()).isEqualTo(written.prevHash());
        assertThat(stored.hash()).isEqualTo(written.hash());
        assertThat(jdbc.queryForObject("select after ->> 'note' from audit.audit_events where seq = ?",
                String.class, written.seq())).isEqualTo("quote \" backslash \\ ₹ नमस्ते");
        rows.assertChainIntact();
    }

    @Test
    void theChainStartsAtGenesis() {
        writeInTx(UUID.randomUUID());

        rows.assertChainIntact(); // the first row's prev_hash is the genesis hash
    }

    /** MANDATORY: an audit row on its own would not be atomic with anything. */
    @Test
    void refusesToWriteWithoutATransaction() {
        assertThatThrownBy(() -> writer.write(entry(UUID.randomUUID()), AuditContext.SYSTEM))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRolledBackCallerLeavesNoRowAndNoGapInTheChain() {
        UUID entity = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            writer.write(entry(entity), AuditContext.SYSTEM);
            status.setRollbackOnly();
        });
        ChainedAuditEvent next = writeInTx(UUID.randomUUID());

        assertThat(rows.forEntity(entity)).isEmpty();
        assertThat(next.prevHash()).isEqualTo(rows.all().stream()
                .filter(r -> r.seq() < next.seq()).reduce((a, b) -> b).orElseThrow().hash());
        rows.assertChainIntact();
    }

    /**
     * 16 threads, 10 transactions each, released together. Without the advisory lock several would read the same
     * last hash; the result must still be one linear chain containing every event.
     */
    @Test
    void concurrentWritersKeepOneLinearChain() throws Exception {
        int threads = 16;
        int perThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<String>>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<String> hashes = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        hashes.add(writeInTx(UUID.randomUUID()).hash());
                    }
                    return hashes;
                }));
            }
            start.countDown();
            Set<String> written = new HashSet<>();
            for (Future<List<String>> future : futures) {
                written.addAll(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(written).hasSize(threads * perThread);
            Set<String> stored = new HashSet<>();
            rows.all().forEach(r -> stored.add(r.hash()));
            assertThat(stored).containsAll(written);
            rows.assertChainIntact();
        } finally {
            pool.shutdownNow();
        }
    }
}
