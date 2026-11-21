package com.trustledger.aml.facts.messaging;

import com.trustledger.aml.facts.application.TransactionFactRecorder;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter for FR-AML-1: reads the contracts core-app publishes and hands them to
 * {@link TransactionFactRecorder}. No logic here; failures propagate to the container's error handler (retries,
 * then the dead-letter topic, see {@code KafkaConsumerConfig}).
 * <p>
 * The {@code correlationId} header goes into the MDC for this record's log lines, so one transfer can be followed
 * from core-app's request into this service. It comes from another system, so only a plain token is accepted
 * (no line breaks or markup in our logs).
 */
@Component
class TransactionFactListener {

    static final String CORRELATION_ID_HEADER = "correlationId";
    static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final TransactionFactRecorder recorder;

    TransactionFactListener(TransactionFactRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(id = "aml-transfer-completed", topics = TransferCompletedV1.TOPIC)
    void onTransferCompleted(ConsumerRecord<String, TransferCompletedV1> record) {
        withCorrelationId(record, () -> recorder.record(record.value()));
    }

    @KafkaListener(id = "aml-topup-captured", topics = TopupCapturedV1.TOPIC)
    void onTopupCaptured(ConsumerRecord<String, TopupCapturedV1> record) {
        withCorrelationId(record, () -> recorder.record(record.value()));
    }

    private static void withCorrelationId(ConsumerRecord<?, ?> record, Runnable work) {
        String correlationId = correlationId(record);
        if (correlationId == null) {
            work.run();
            return;
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, correlationId)) {
            work.run();
        }
    }

    /** The header's value if it is a plain token, otherwise {@code null}. */
    static String correlationId(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(CORRELATION_ID_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        return SAFE_ID.matcher(value).matches() ? value : null;
    }
}
