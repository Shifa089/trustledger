package com.trustledger.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditActor;
import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.audit.domain.AuditEventStore;
import com.trustledger.core.audit.domain.AuditHashing;
import com.trustledger.core.audit.domain.ChainedAuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AuditWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-22T09:00:00.123456789Z");

    private final AuditEventStore store = mock(AuditEventStore.class);
    private final AuditWriter writer = new AuditWriter(store, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID officer = UUID.randomUUID();
    private final AuditContext context = new AuditContext(new AuditActor(officer, "COMPLIANCE_OFFICER"),
            "203.0.113.7", "corr-1");

    private static AuditEntry entry(Map<String, ?> after) {
        return new AuditEntry(AuditAction.WALLET_FROZEN, AuditEntityType.WALLET, UUID.randomUUID(),
                Map.of("status", "ACTIVE"), after);
    }

    @Test
    void theFirstEventLinksToGenesis() {
        when(store.latestHash()).thenReturn(Optional.empty());
        when(store.append(any(), any(), any(), any())).thenReturn(1L);

        ChainedAuditEvent written = writer.write(entry(Map.of("status", "FROZEN")), context);

        assertThat(written.seq()).isEqualTo(1L);
        assertThat(written.prevHash()).isEqualTo(AuditHashing.GENESIS_HASH);
        assertThat(written.hash()).isEqualTo(AuditHashing.hash(AuditHashing.GENESIS_HASH, written.event()));
    }

    @Test
    void aLaterEventLinksToTheLatestHash() {
        String latest = "ab".repeat(32);
        when(store.latestHash()).thenReturn(Optional.of(latest));
        when(store.append(any(), any(), any(), any())).thenReturn(42L);

        ChainedAuditEvent written = writer.write(entry(Map.of("status", "FROZEN")), context);

        assertThat(written.prevHash()).isEqualTo(latest);
        assertThat(written.hash()).isEqualTo(AuditHashing.hash(latest, written.event()));
    }

    /** Read-after-lock is the whole point: reading first would let two writers extend the same hash. */
    @Test
    void locksBeforeReadingAndInsertsLast() {
        when(store.latestHash()).thenReturn(Optional.empty());
        writer.write(entry(Map.of("status", "FROZEN")), context);

        InOrder order = inOrder(store);
        order.verify(store).lockChain();
        order.verify(store).latestHash();
        order.verify(store).append(any(), eq(AuditHashing.GENESIS_HASH), any(), eq(NOW));
    }

    @Test
    void storesTheMaskedEventWithContextAndClockTime() {
        when(store.latestHash()).thenReturn(Optional.empty());
        ArgumentCaptor<AuditEvent> stored = ArgumentCaptor.forClass(AuditEvent.class);

        writer.write(entry(Map.of("status", "FROZEN", "email", "officer@example.test")), context);

        org.mockito.Mockito.verify(store).append(stored.capture(), any(), any(), any());
        AuditEvent event = stored.getValue();
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-22T09:00:00.123456Z"));
        assertThat(event.actorId()).isEqualTo(officer);
        assertThat(event.actorRole()).isEqualTo("COMPLIANCE_OFFICER");
        assertThat(event.ip()).isEqualTo("203.0.113.7");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.after()).containsEntry("email", "o***@example.test").containsEntry("status", "FROZEN");
    }

    @Test
    void aBadEntryFailsBeforeTheLockIsTaken() {
        assertThatThrownBy(() -> writer.write(entry(Map.of("amount", 1.5)), context))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }
}
