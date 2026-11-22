package com.trustledger.aml.cases.messaging;

import com.trustledger.aml.cases.application.FreezeRelayProperties;
import com.trustledger.aml.cases.domain.FreezeRequestPublisher;
import com.trustledger.events.AccountFreezeRequestedV1;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link FreezeRequestPublisher} with a {@link KafkaTemplate}, in the shape core-app's outbox uses for its own
 * contracts (SPEC §6 "Kafka"): JSON value, {@code __TypeId__} header naming the contract record, the wallet ID as
 * key (one wallet's requests stay in order), {@code eventId} and {@code correlationId} headers as UTF-8 bytes
 * ({@code correlationId} left out when there is none).
 */
@Component
class KafkaFreezeRequestPublisher implements FreezeRequestPublisher {

    static final String EVENT_ID_HEADER = "eventId";
    static final String CORRELATION_ID_HEADER = "correlationId";

    private final KafkaTemplate<String, Object> template;
    private final FreezeRelayProperties properties;

    KafkaFreezeRequestPublisher(@Qualifier("freezeRequestTemplate") KafkaTemplate<String, Object> template,
            FreezeRelayProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public void publish(AccountFreezeRequestedV1 event, String correlationId) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(AccountFreezeRequestedV1.TOPIC, null,
                event.walletId().toString(), event);
        record.headers().add(EVENT_ID_HEADER, event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        if (correlationId != null) {
            record.headers().add(CORRELATION_ID_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            template.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FreezeRequestPublishException("Interrupted while sending the freeze request", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new FreezeRequestPublishException("The broker did not acknowledge the freeze request", e);
        }
    }
}
