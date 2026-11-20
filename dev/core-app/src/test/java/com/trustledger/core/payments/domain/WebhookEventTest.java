package com.trustledger.core.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebhookEventTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant LATER = RECEIVED.plusSeconds(1);
    private static final String PAYLOAD = "{\"event\":\"payment.captured\",\"payload\":{}}";

    private static WebhookEvent accepted() {
        return WebhookEvent.accepted(UUID.randomUUID(), "evt_1", "payment.captured", PAYLOAD, RECEIVED);
    }

    @Test
    void acceptedEventIsValidAndUnprocessed() {
        WebhookEvent event = accepted();
        assertThat(event.signatureValid()).isTrue();
        assertThat(event.isProcessed()).isFalse();
        assertThat(event.processedAt()).isNull();
    }

    @Test
    void acceptedEventNeedsIdTypeAndPayload() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> WebhookEvent.accepted(UUID.randomUUID(), null, "payment.captured", PAYLOAD, RECEIVED));
        assertThatIllegalArgumentException().isThrownBy(
                () -> WebhookEvent.accepted(UUID.randomUUID(), "evt_1", null, PAYLOAD, RECEIVED));
        assertThatIllegalArgumentException().isThrownBy(
                () -> WebhookEvent.accepted(UUID.randomUUID(), "evt_1", "payment.captured", null, RECEIVED));
    }

    @Test
    void rejectedDeliveryMayLackEverything() {
        WebhookEvent event = WebhookEvent.rejected(UUID.randomUUID(), null, null, null, RECEIVED);
        assertThat(event.signatureValid()).isFalse();
        assertThat(event.isProcessed()).isFalse();
    }

    @Test
    void rejectedDeliveryCannotBeConstructedAsProcessed() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new WebhookEvent(UUID.randomUUID(), "evt_1", "x", PAYLOAD, false, RECEIVED, LATER));
    }

    @Test
    void markProcessedSetsTheTimeAndKeepsEverythingElse() {
        WebhookEvent event = accepted();
        WebhookEvent processed = event.markProcessed(LATER);
        assertThat(processed.processedAt()).isEqualTo(LATER);
        assertThat(processed.isProcessed()).isTrue();
        assertThat(processed).usingRecursiveComparison().ignoringFields("processedAt").isEqualTo(event);
    }

    @Test
    void processedEventCannotBeProcessedAgain() {
        WebhookEvent processed = accepted().markProcessed(LATER);
        assertThatThrownBy(() -> processed.markProcessed(LATER.plusSeconds(1)))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void rejectedDeliveryIsNeverProcessed() {
        WebhookEvent rejected = WebhookEvent.rejected(UUID.randomUUID(), "evt_1", "payment.captured", PAYLOAD,
                RECEIVED);
        assertThatThrownBy(() -> rejected.markProcessed(LATER))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void toStringNeverContainsThePayload() {
        assertThat(accepted().toString()).contains("evt_1").doesNotContain("payload\"");
    }
}
