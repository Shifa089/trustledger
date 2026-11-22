package com.trustledger.core.privacy.messaging;

import com.trustledger.core.privacy.application.AmlCaseProjectionService;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import com.trustledger.events.AmlCaseStatusChangedV1;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter: reads {@link AmlCaseStatusChangedV1} from risk-aml-service into core-app's list of open AML cases,
 * which erasure checks (FR-PR-2). No logic here; failures propagate to the container's error handler (retries, then
 * {@code <topic>.DLT}, see {@code KafkaConsumerConfig}). The decision uses the {@code open} flag, never the status
 * name, so a status added later on the AML side needs no change here.
 */
@Component
class AmlCaseStatusChangedListener {

    static final String CORRELATION_ID_HEADER = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final AmlCaseProjectionService projection;

    AmlCaseStatusChangedListener(AmlCaseProjectionService projection) {
        this.projection = projection;
    }

    /** {@code void}: a listener that returns a value is a request/reply listener to Spring Kafka. */
    @KafkaListener(id = "core-aml-case-status-changed", topics = AmlCaseStatusChangedV1.TOPIC)
    void onCaseStatusChanged(ConsumerRecord<String, AmlCaseStatusChangedV1> record) {
        AmlCaseStatusChangedV1 event = record.value();
        String correlationId = correlationId(record);
        if (correlationId == null) {
            apply(event);
            return;
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId)) {
            apply(event);
        }
    }

    private void apply(AmlCaseStatusChangedV1 event) {
        projection.apply(event.caseId(), event.walletId(), event.open(), event.occurredAt());
    }

    /** The header's value if it is a plain token, otherwise {@code null}: it comes from another system. */
    static String correlationId(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(CORRELATION_ID_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        return SAFE_ID.matcher(value).matches() ? value : null;
    }
}
