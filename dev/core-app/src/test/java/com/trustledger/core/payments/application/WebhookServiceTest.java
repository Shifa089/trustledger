package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.domain.WebhookEvent;
import com.trustledger.core.payments.domain.WebhookSignatureVerifier;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

class WebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final byte[] BODY = """
            {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_1","email":"a@example.test"}}}}"""
            .getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "s".repeat(64);

    private final WebhookSignatureVerifier verifier = mock(WebhookSignatureVerifier.class);
    private final WebhookWriter writer = mock(WebhookWriter.class);
    private final WebhookProcessor processor = mock(WebhookProcessor.class);
    private final WebhookService service = new WebhookService(verifier,
            new WebhookPayloads(JsonMapper.builder().build()), writer, processor, Clock.fixed(NOW, ZoneOffset.UTC));

    private WebhookEvent storedRejected() {
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(writer).storeRejected(captor.capture());
        return captor.getValue();
    }

    private static void failsWith(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void validEventIsStoredThenProcessed() {
        when(verifier.isValid(BODY, SIGNATURE)).thenReturn(true);
        when(processor.process("evt_1")).thenReturn(WebhookOutcome.PROCESSED);

        assertThat(service.receive(BODY, SIGNATURE, "evt_1")).isEqualTo(WebhookOutcome.PROCESSED);

        InOrder order = inOrder(writer, processor);
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        order.verify(writer).storeAccepted(captor.capture());
        order.verify(processor).process("evt_1");
        WebhookEvent stored = captor.getValue();
        assertThat(stored.signatureValid()).isTrue();
        assertThat(stored.gatewayEventId()).isEqualTo("evt_1");
        assertThat(stored.eventType()).isEqualTo("payment.captured");
        assertThat(stored.receivedAt()).isEqualTo(NOW);
        assertThat(stored.payload()).contains("pay_1").doesNotContain("example.test");
        verify(writer, never()).storeRejected(any());
    }

    @Test
    void outcomeOfTheProcessorIsReturned() {
        when(verifier.isValid(BODY, SIGNATURE)).thenReturn(true);
        when(processor.process("evt_1")).thenReturn(WebhookOutcome.DUPLICATE);
        assertThat(service.receive(BODY, SIGNATURE, "evt_1")).isEqualTo(WebhookOutcome.DUPLICATE);
    }

    @Test
    void invalidSignatureIsStoredAsRejectedAndNeverProcessed() {
        when(verifier.isValid(BODY, SIGNATURE)).thenReturn(false);

        failsWith(() -> service.receive(BODY, SIGNATURE, "evt_1"), ErrorCode.WEBHOOK_SIGNATURE_INVALID);

        WebhookEvent rejected = storedRejected();
        assertThat(rejected.signatureValid()).isFalse();
        assertThat(rejected.gatewayEventId()).isEqualTo("evt_1");
        assertThat(rejected.eventType()).isEqualTo("payment.captured");
        assertThat(rejected.payload()).doesNotContain("example.test");
        verify(writer, never()).storeAccepted(any());
        verify(processor, never()).process(anyString());
    }

    @Test
    void missingSignatureIsInvalid() {
        when(verifier.isValid(BODY, null)).thenReturn(false);
        failsWith(() -> service.receive(BODY, null, "evt_1"), ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        assertThat(storedRejected().signatureValid()).isFalse();
    }

    @Test
    void invalidSignatureWithGarbageIsStoredWithWhateverCouldBeRead() {
        byte[] garbage = "not json".getBytes(StandardCharsets.UTF_8);
        when(verifier.isValid(garbage, SIGNATURE)).thenReturn(false);

        failsWith(() -> service.receive(garbage, SIGNATURE, "evt 1; drop table"), ErrorCode.WEBHOOK_SIGNATURE_INVALID);

        WebhookEvent rejected = storedRejected();
        assertThat(rejected.gatewayEventId()).as("malformed event ID is not stored").isNull();
        assertThat(rejected.eventType()).isNull();
        assertThat(rejected.payload()).isNull();
    }

    @Test
    void signedEventWithoutEventIdIsRejected() {
        when(verifier.isValid(BODY, SIGNATURE)).thenReturn(true);
        failsWith(() -> service.receive(BODY, SIGNATURE, null), ErrorCode.VALIDATION_FAILED);
        assertThat(storedRejected().signatureValid()).isFalse();
        verify(processor, never()).process(anyString());
    }

    @Test
    void signedEventWithoutTypeIsRejected() {
        byte[] noType = "{\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        when(verifier.isValid(noType, SIGNATURE)).thenReturn(true);
        failsWith(() -> service.receive(noType, SIGNATURE, "evt_1"), ErrorCode.VALIDATION_FAILED);
        assertThat(storedRejected().gatewayEventId()).isEqualTo("evt_1");
        verify(processor, never()).process(anyString());
    }

    @Test
    void processingFailurePropagatesAfterTheEventWasStored() {
        when(verifier.isValid(BODY, SIGNATURE)).thenReturn(true);
        when(processor.process("evt_1")).thenThrow(new IllegalStateException("handler failed"));

        assertThatThrownBy(() -> service.receive(BODY, SIGNATURE, "evt_1")).hasMessage("handler failed");
        verify(writer).storeAccepted(any());
    }

    @Test
    void runsWithoutATransactionSoTheStoredRowIsCommittedFirst() throws NoSuchMethodException {
        Transactional tx = WebhookService.class
                .getMethod("receive", byte[].class, String.class, String.class).getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.NEVER);
    }

    @Test
    void processorRunsInItsOwnTransaction() throws NoSuchMethodException {
        Transactional tx = WebhookProcessor.class.getMethod("process", String.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
