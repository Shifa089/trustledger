package com.trustledger.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditActor;
import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.shared.audit.AuditSnapshotSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditedActionRecorderTest {

    private static final UUID WALLET = UUID.randomUUID();

    private final AuditWriter writer = mock(AuditWriter.class);
    private final AuditContextProvider contextProvider = mock(AuditContextProvider.class);
    private final AuditContext context = new AuditContext(new AuditActor(UUID.randomUUID(), "COMPLIANCE_OFFICER"),
            "203.0.113.7", "corr-1", "Agent/1");

    private static AuditSnapshotSource source(String type, Map<String, Object> state) {
        return new AuditSnapshotSource() {
            @Override
            public String entityType() {
                return type;
            }

            @Override
            public Optional<Map<String, Object>> snapshot(UUID id) {
                return Optional.ofNullable(state);
            }
        };
    }

    private AuditedActionRecorder recorder(AuditSnapshotSource... sources) {
        when(contextProvider.current()).thenReturn(context);
        return new AuditedActionRecorder(List.of(sources), writer, contextProvider);
    }

    private AuditEntry written() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(writer).write(entry.capture(), eq(context));
        return entry.getValue();
    }

    @Test
    void aSuccessRecordsTheStateNowTheDetailsAndTheOutcome() {
        AuditedActionRecorder recorder = recorder(source("WALLET", Map.of("status", "FROZEN", "tier", "FULL")));

        recorder.succeeded(AuditAction.WALLET_FREEZE, AuditEntityType.WALLET, WALLET, Map.of("status", "ACTIVE"),
                Map.of("reasonLength", 20));

        AuditEntry entry = written();
        assertThat(entry.action()).isEqualTo(AuditAction.WALLET_FREEZE);
        assertThat(entry.entityType()).isEqualTo(AuditEntityType.WALLET);
        assertThat(entry.entityId()).isEqualTo(WALLET);
        assertThat(entry.before()).isEqualTo(Map.of("status", "ACTIVE"));
        assertThat(entry.after()).isEqualTo(Map.of("status", "FROZEN", "tier", "FULL", "reasonLength", 20,
                "outcome", "SUCCEEDED"));
    }

    @Test
    void aRejectionRecordsTheErrorCodeAndNoNewState() {
        AuditedActionRecorder recorder = recorder(source("WALLET", Map.of("status", "FROZEN")));

        recorder.rejected(AuditAction.WALLET_FREEZE, AuditEntityType.WALLET, WALLET, Map.of("status", "FROZEN"),
                Map.of("reasonLength", 5), "INVALID_STATE_TRANSITION");

        assertThat(written().after()).isEqualTo(Map.of("reasonLength", 5, "outcome", "REJECTED",
                "errorCode", "INVALID_STATE_TRANSITION"));
    }

    @Test
    void theOutcomeCannotBeOverwrittenByASnapshotOrADetail() {
        AuditedActionRecorder recorder = recorder(source("WALLET", Map.of("outcome", "FAKE")));

        recorder.succeeded(AuditAction.WALLET_FREEZE, AuditEntityType.WALLET, WALLET, null,
                Map.of("outcome", "ALSO_FAKE"));

        assertThat(written().after().get("outcome")).isEqualTo("SUCCEEDED");
    }

    @Test
    void aTypeWithoutSourceOrAMissingEntityHasNoSnapshot() {
        AuditedActionRecorder recorder = recorder(source("WALLET", null));

        assertThat(recorder.snapshot(AuditEntityType.RECONCILIATION_RUN, UUID.randomUUID())).isNull();
        assertThat(recorder.snapshot(AuditEntityType.WALLET, UUID.randomUUID())).isNull();

        recorder.succeeded(AuditAction.RECONCILIATION_RUN, AuditEntityType.RECONCILIATION_RUN, WALLET, null,
                Map.of("walletsChecked", 3L));
        assertThat(written().after()).isEqualTo(Map.of("walletsChecked", 3L, "outcome", "SUCCEEDED"));
    }

    @Test
    void twoSourcesForOneTypeStopStartup() {
        assertThatThrownBy(() -> new AuditedActionRecorder(
                List.of(source("WALLET", null), source("WALLET", null)), writer, contextProvider))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WALLET");
    }

    @Test
    void writerFailuresPropagateToTheCaller() {
        AuditedActionRecorder recorder = recorder();
        when(writer.write(any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> recorder.succeeded(AuditAction.RECONCILIATION_RUN,
                AuditEntityType.RECONCILIATION_RUN, WALLET, null, Map.of()))
                .hasMessage("db down");
    }
}
