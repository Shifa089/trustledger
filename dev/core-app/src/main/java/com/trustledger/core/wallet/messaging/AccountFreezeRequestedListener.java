package com.trustledger.core.wallet.messaging;

import com.trustledger.core.shared.web.CorrelationIdFilter;
import com.trustledger.core.wallet.AmlFreezeOutcome;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.events.AccountFreezeRequestedV1;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter for FR-AML-3: reads {@link AccountFreezeRequestedV1} from risk-aml-service and freezes the wallet
 * through {@link WalletApi#freezeForAml}, which is idempotent by {@code eventId}. No logic here; failures propagate
 * to the container's error handler (retries, then {@code <topic>.DLT}, see {@code KafkaConsumerConfig}).
 * <p>
 * The {@code correlationId} header goes into the MDC, so the freeze's log lines and its {@code WalletFrozen} event
 * carry the AML record's correlation ID. It comes from another system, so only a plain token is accepted.
 */
@Component
class AccountFreezeRequestedListener {

    static final String CORRELATION_ID_HEADER = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(AccountFreezeRequestedListener.class);

    private final WalletApi wallets;

    AccountFreezeRequestedListener(WalletApi wallets) {
        this.wallets = wallets;
    }

    /**
     * {@code void} on purpose: a listener method that returns a value is a request/reply listener to Spring Kafka,
     * which then tries to send the value as a reply and fails every record.
     */
    @KafkaListener(id = "core-account-freeze-requested", topics = AccountFreezeRequestedV1.TOPIC)
    void onFreezeRequested(ConsumerRecord<String, AccountFreezeRequestedV1> record) {
        AccountFreezeRequestedV1 event = record.value();
        String correlationId = correlationId(record);
        if (correlationId == null) {
            freeze(event);
            return;
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId)) {
            freeze(event);
        }
    }

    private void freeze(AccountFreezeRequestedV1 event) {
        AmlFreezeOutcome outcome = wallets.freezeForAml(event.eventId(), event.walletId(), event.caseId(),
                event.ruleCode());
        log.debug("AML freeze request handled eventId={} outcome={}", event.eventId(), outcome);
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
