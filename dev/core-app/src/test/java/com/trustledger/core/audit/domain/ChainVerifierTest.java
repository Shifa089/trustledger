package com.trustledger.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.audit.domain.ChainVerification.BreakReason;
import com.trustledger.core.audit.domain.ChainVerification.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-A-2: the verifier finds the first broken row, and says why, for every kind of tampering. */
class ChainVerifierTest {

    private static final UUID OFFICER = UUID.fromString("00000000-0000-7000-8000-0000000000aa");

    /** A valid chain of {@code n} rows with seq 10, 20, 30, ... (gaps, as a rolled-back writer leaves). */
    static List<StoredAuditEvent> chain(int n) {
        List<StoredAuditEvent> rows = new ArrayList<>();
        String prev = AuditHashing.GENESIS_HASH;
        for (int i = 1; i <= n; i++) {
            AuditEvent event = new AuditEvent(Instant.parse("2026-11-22T10:00:00Z").plusSeconds(i), OFFICER,
                    "COMPLIANCE_OFFICER", "WALLET_FREEZE", "WALLET", UUID.randomUUID(), Map.of("status", "ACTIVE"),
                    Map.of("status", "FROZEN", "reasonLength", 12), "203.0.113.7", "corr-" + i,
                    i % 2 == 0 ? "Agent/" + i : null);
            String hash = AuditHashing.hash(prev, event);
            rows.add(stored(i * 10L, event, prev, hash));
            prev = hash;
        }
        return rows;
    }

    static StoredAuditEvent stored(long seq, AuditEvent e, String prevHash, String hash) {
        return new StoredAuditEvent(seq, e.occurredAt(), e.actorId(), e.actorRole(), e.action(), e.entityType(),
                e.entityId(), e.before(), e.after(), e.ip(), e.userAgent(), e.correlationId(), prevHash, hash);
    }

    private static ChainVerification verify(List<StoredAuditEvent> rows) {
        ChainVerifier verifier = new ChainVerifier();
        rows.forEach(verifier::accept);
        return verifier.result();
    }

    private static StoredAuditEvent withAfter(StoredAuditEvent r, Object after) {
        return new StoredAuditEvent(r.seq(), r.occurredAt(), r.actorId(), r.actorRole(), r.action(), r.entityType(),
                r.entityId(), r.before(), after, r.ip(), r.userAgent(), r.correlationId(), r.prevHash(), r.hash());
    }

    @Test
    void anIntactChainIsOkWithItsLastLink() {
        List<StoredAuditEvent> rows = chain(5);

        ChainVerification result = verify(rows);

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.checkedEvents()).isEqualTo(5);
        assertThat(result.lastSeq()).isEqualTo(50L);
        assertThat(result.lastHash()).isEqualTo(rows.getLast().hash());
        assertThat(result.brokenSeq()).isNull();
        assertThat(result.reason()).isNull();
    }

    @Test
    void anEmptyChainIsOk() {
        ChainVerification result = new ChainVerifier().result();

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.checkedEvents()).isZero();
        assertThat(result.lastSeq()).isNull();
        assertThat(result.lastHash()).isNull();
    }

    @Test
    void anEditedRowIsReportedAtThatRow() {
        List<StoredAuditEvent> rows = chain(5);
        rows.set(2, withAfter(rows.get(2), Map.of("status", "ACTIVE", "reasonLength", 12)));

        ChainVerification result = verify(rows);

        assertThat(result.status()).isEqualTo(Status.BROKEN);
        assertThat(result.brokenSeq()).isEqualTo(30L);
        assertThat(result.reason()).isEqualTo(BreakReason.HASH_MISMATCH);
        assertThat(result.checkedEvents()).isEqualTo(2);
        assertThat(result.lastSeq()).isEqualTo(20L);
        assertThat(result.lastHash()).isEqualTo(rows.get(1).hash());
    }

    @Test
    void anEditedUserAgentIsDetected() {
        List<StoredAuditEvent> rows = chain(4);
        StoredAuditEvent r = rows.get(1); // seq 20 has a user agent
        rows.set(1, new StoredAuditEvent(r.seq(), r.occurredAt(), r.actorId(), r.actorRole(), r.action(),
                r.entityType(), r.entityId(), r.before(), r.after(), r.ip(), "Spoofed/1.0", r.correlationId(),
                r.prevHash(), r.hash()));

        assertThat(verify(rows).brokenSeq()).isEqualTo(20L);
    }

    @Test
    void aRehashedRowIsReportedAtTheNextRow() {
        // The attacker edits row 30 and recomputes its own hash; row 40 still points at the old one.
        List<StoredAuditEvent> rows = chain(5);
        StoredAuditEvent edited = withAfter(rows.get(2), Map.of("status", "ACTIVE"));
        rows.set(2, new StoredAuditEvent(edited.seq(), edited.occurredAt(), edited.actorId(), edited.actorRole(),
                edited.action(), edited.entityType(), edited.entityId(), edited.before(), edited.after(), edited.ip(),
                edited.userAgent(), edited.correlationId(), edited.prevHash(),
                AuditHashing.hash(edited.prevHash(), edited.toEvent())));

        ChainVerification result = verify(rows);

        assertThat(result.brokenSeq()).isEqualTo(40L);
        assertThat(result.reason()).isEqualTo(BreakReason.PREV_HASH_MISMATCH);
    }

    @Test
    void aDeletedRowIsReportedAtTheRowAfterIt() {
        List<StoredAuditEvent> rows = chain(5);
        rows.remove(2); // seq 30

        ChainVerification result = verify(rows);

        assertThat(result.brokenSeq()).isEqualTo(40L);
        assertThat(result.reason()).isEqualTo(BreakReason.PREV_HASH_MISMATCH);
    }

    @Test
    void aSwappedPairIsReportedAtTheFirstOfThem() {
        List<StoredAuditEvent> rows = chain(5);
        StoredAuditEvent second = rows.get(1);
        rows.set(1, rows.get(2));
        rows.set(2, second);

        assertThat(verify(rows).brokenSeq()).isEqualTo(30L);
    }

    @Test
    void aChainThatDoesNotStartAtGenesisIsBrokenAtItsFirstRow() {
        List<StoredAuditEvent> rows = chain(3);
        rows.removeFirst();

        ChainVerification result = verify(rows);

        assertThat(result.brokenSeq()).isEqualTo(20L);
        assertThat(result.lastSeq()).isNull();
        assertThat(result.lastHash()).isNull();
    }

    @Test
    void aStateThatIsNoLongerAnObjectIsUnreadable() {
        List<StoredAuditEvent> rows = chain(3);
        rows.set(1, withAfter(rows.get(1), List.of("not", "an", "object")));

        ChainVerification result = verify(rows);

        assertThat(result.brokenSeq()).isEqualTo(20L);
        assertThat(result.reason()).isEqualTo(BreakReason.UNREADABLE);
    }

    @Test
    void aFractionInTheStateIsUnreadable() {
        List<StoredAuditEvent> rows = chain(2);
        rows.set(0, withAfter(rows.getFirst(), Map.of("amount", 1.5)));

        assertThat(verify(rows).reason()).isEqualTo(BreakReason.UNREADABLE);
    }

    @Test
    void aMissingRequiredColumnIsUnreadable() {
        List<StoredAuditEvent> rows = chain(1);
        StoredAuditEvent r = rows.getFirst();
        rows.set(0, new StoredAuditEvent(r.seq(), r.occurredAt(), r.actorId(), null, r.action(), r.entityType(),
                r.entityId(), r.before(), r.after(), r.ip(), r.userAgent(), r.correlationId(), r.prevHash(),
                r.hash()));

        assertThat(verify(rows).reason()).isEqualTo(BreakReason.UNREADABLE);
    }

    @Test
    void rowsAfterABreakAreIgnored() {
        List<StoredAuditEvent> rows = chain(4);
        rows.remove(1);
        ChainVerifier verifier = new ChainVerifier();

        assertThat(verifier.accept(rows.get(0))).isTrue();
        assertThat(verifier.accept(rows.get(1))).isFalse();
        assertThat(verifier.isBroken()).isTrue();
        assertThat(verifier.accept(rows.get(2))).isFalse();
        assertThat(verifier.result().brokenSeq()).isEqualTo(30L);
    }
}
