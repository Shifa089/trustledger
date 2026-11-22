package com.trustledger.aml.cases.messaging;

import com.trustledger.aml.cases.application.FreezeRelayProperties;
import com.trustledger.aml.cases.domain.CaseEventPublisher;
import com.trustledger.events.AmlCaseStatusChangedV1;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link CaseEventPublisher} with the same producer and record shape as {@link KafkaFreezeRequestPublisher}: JSON
 * value with a {@code __TypeId__} header, the wallet ID as key (one wallet's events stay in order), {@code eventId}
 * and {@code correlationId} headers.
 */
@Component
class KafkaCaseEventPublisher implements CaseEventPublisher {

    private final KafkaTemplate<String, Object> template;
    private final FreezeRelayProperties properties;

    KafkaCaseEventPublisher(@Qualifier("freezeRequestTemplate") KafkaTemplate<String, Object> template,
            FreezeRelayProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    @Override
    public void publish(AmlCaseStatusChangedV1 event, String correlationId) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(AmlCaseStatusChangedV1.TOPIC, null,
                event.walletId().toString(), event);
        record.headers().add(KafkaFreezeRequestPublisher.EVENT_ID_HEADER,
                event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        if (correlationId != null) {
            record.headers().add(KafkaFreezeRequestPublisher.CORRELATION_ID_HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            template.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending the case event", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new IllegalStateException("The broker did not acknowledge the case event", e);
        }
    }
}
