package com.trustledger.core.shared.events;

import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.ExternalizedEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.Externalized;

/**
 * Which outbox events go to Kafka, and in what shape (M8, ADR 0005 part B).
 * <ul>
 * <li><b>Selection and routing</b>: events annotated with {@link Externalized} that implement
 * {@link ExternalizedEvent}. The annotation names the topic (the contract's {@code TOPIC}) and the message key (the
 * wallet ID); Modulith evaluates it on the internal event, before mapping.</li>
 * <li><b>Payload</b>: the contract ({@link ExternalizedEvent#toV1()}), never the internal event, which can hold
 * data that must not leave core-app.</li>
 * <li><b>Headers</b> {@value #EVENT_ID_HEADER} and {@value #CORRELATION_ID_HEADER} (left out when there is none,
 * e.g. a scheduled job). Modulith computes configured headers from the <i>mapped</i> object, which has no
 * correlation ID, so the mapping returns a {@link Message} carrying them; the Kafka transport sends a Message as
 * it is. Values are UTF-8 bytes, so any consumer reads them without Spring's JSON header types.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventsProperties.class)
public class EventExternalizationConfig {

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String CORRELATION_ID_HEADER = "correlationId";

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(EventExternalizationConfiguration.annotatedAsExternalized()
                        .and(ExternalizedEvent.class::isInstance))
                .mapping(ExternalizedEvent.class, EventExternalizationConfig::toMessage)
                .build();
    }

    /** The contract with its identity headers. */
    static Message<Record> toMessage(ExternalizedEvent event) {
        EventMetadata metadata = event.metadata();
        MessageBuilder<Record> message = MessageBuilder.withPayload(event.toV1())
                .setHeader(EVENT_ID_HEADER, utf8(metadata.eventId().toString()));
        if (metadata.correlationId() != null) {
            message.setHeader(CORRELATION_ID_HEADER, utf8(metadata.correlationId()));
        }
        return message.build();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
