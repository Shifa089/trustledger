package com.trustledger.core.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.audit.application.AuditedActionRecorder;
import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.audit.AuditDetails;
import com.trustledger.core.shared.audit.Audited;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

/** The aspect on its own: one audit event per call, the outcome, and never a lost response. */
class AuditedAspectTest {

    private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-000000000042");

    record Reason(String reason) implements AuditDetails {
        @Override
        public Map<String, Object> auditDetails() {
            return Map.of("reasonLength", reason.length());
        }
    }

    record Report(long checked) implements AuditDetails {
        @Override
        public Map<String, Object> auditDetails() {
            return Map.of("checked", checked);
        }
    }

    /** Stands in for a controller. */
    static class FakeEndpoints {

        RuntimeException failure;

        @Audited(action = "WALLET_FREEZE", entityType = "WALLET")
        public String freeze(@PathVariable UUID id, Reason request) {
            if (failure != null) {
                throw failure;
            }
            return "frozen";
        }

        @Audited(action = "KYC_DOCUMENT_VIEW", entityType = "KYC_DOCUMENT", entityIdParam = "docId",
                captureBefore = false)
        public ResponseEntity<Report> view(@PathVariable UUID id, @PathVariable("docId") UUID document) {
            return ResponseEntity.ok(new Report(9));
        }

        @Audited(action = "RECONCILIATION_RUN", entityType = "RECONCILIATION_RUN", entityIdParam = "",
                captureBefore = false)
        public Report run() {
            return new Report(3);
        }

        @Audited(action = "WALLET_UNFREEZE", entityType = "WALLET", entityIdParam = "missing")
        public String misconfigured(@PathVariable UUID id) {
            return "never";
        }

        public String notAudited() {
            return "plain";
        }
    }

    private final AuditedActionRecorder recorder = mock(AuditedActionRecorder.class);
    private final FakeEndpoints target = new FakeEndpoints();
    private FakeEndpoints proxy;

    @BeforeEach
    void setUp() {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new AuditedAspect(recorder,
                Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC)));
        proxy = factory.getProxy();
        when(recorder.snapshot(AuditEntityType.WALLET, ID)).thenReturn(Map.of("status", "ACTIVE"));
    }

    @Test
    void aSuccessfulCallIsRecordedWithBeforeStateAndRequestDetails() {
        assertThat(proxy.freeze(ID, new Reason("fraud report"))).isEqualTo("frozen");

        verify(recorder).succeeded(AuditAction.WALLET_FREEZE, AuditEntityType.WALLET, ID,
                Map.of("status", "ACTIVE"), Map.of("reasonLength", 12));
    }

    @Test
    void aBusinessRefusalIsRecordedAsRejectedAndStillThrown() {
        target.failure = new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION, "Already frozen.");

        assertThatThrownBy(() -> proxy.freeze(ID, new Reason("again"))).isSameAs(target.failure);

        verify(recorder).rejected(AuditAction.WALLET_FREEZE, AuditEntityType.WALLET, ID,
                Map.of("status", "ACTIVE"), Map.of("reasonLength", 5), "INVALID_STATE_TRANSITION");
        verify(recorder, never()).succeeded(any(), any(), any(), any(), any());
    }

    @Test
    void anUnexpectedFailureIsNotAudited() {
        target.failure = new IllegalStateException("bug");

        assertThatThrownBy(() -> proxy.freeze(ID, new Reason("x"))).isSameAs(target.failure);

        verify(recorder, never()).succeeded(any(), any(), any(), any(), any());
        verify(recorder, never()).rejected(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void aFailedAuditWriteNeverLosesTheCommittedResponse() {
        doThrow(new IllegalStateException("audit db down")).when(recorder)
                .succeeded(any(), any(), any(), any(), any());

        assertThat(proxy.freeze(ID, new Reason("fraud"))).isEqualTo("frozen");
    }

    @Test
    void aFailedAuditWriteNeverHidesTheRefusal() {
        TrustLedgerException refusal = new TrustLedgerException(ErrorCode.NOT_FOUND, "Wallet not found.");
        target.failure = refusal;
        doThrow(new IllegalStateException("audit db down")).when(recorder)
                .rejected(any(), any(), any(), any(), any(), anyString());

        assertThatThrownBy(() -> proxy.freeze(ID, new Reason("x"))).isSameAs(refusal);
    }

    @Test
    void aFailedBeforeSnapshotStillAuditsTheCall() {
        when(recorder.snapshot(AuditEntityType.WALLET, ID)).thenThrow(new IllegalStateException("read failed"));

        proxy.freeze(ID, new Reason("fraud"));

        verify(recorder).succeeded(eq(AuditAction.WALLET_FREEZE), eq(AuditEntityType.WALLET), eq(ID), isNull(),
                anyMap());
    }

    @Test
    void theEntityIdComesFromTheNamedPathVariableAndResponseBodyDetailsAreAdded() {
        UUID document = UUID.randomUUID();

        proxy.view(ID, document);

        verify(recorder, never()).snapshot(any(), any());
        verify(recorder).succeeded(AuditAction.KYC_DOCUMENT_VIEW, AuditEntityType.KYC_DOCUMENT, document, null,
                Map.of("checked", 9L));
    }

    @Test
    void anActionWithoutEntityGetsANewTimeOrderedId() {
        proxy.run();

        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(recorder).succeeded(eq(AuditAction.RECONCILIATION_RUN), eq(AuditEntityType.RECONCILIATION_RUN),
                id.capture(), isNull(), eq(Map.of("checked", 3L)));
        assertThat(id.getValue().version()).isEqualTo(7);
    }

    @Test
    void aMisconfiguredEntityParameterFailsTheCallBeforeItRuns() {
        assertThatThrownBy(() -> proxy.misconfigured(ID)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
        verifyNoInteractions(recorder);
    }

    @Test
    void methodsWithoutTheAnnotationAreLeftAlone() {
        assertThat(proxy.notAudited()).isEqualTo("plain");
        verifyNoInteractions(recorder);
    }
}
