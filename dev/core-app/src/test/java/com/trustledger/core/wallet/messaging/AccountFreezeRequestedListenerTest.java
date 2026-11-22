package com.trustledger.core.wallet.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.shared.web.CorrelationIdFilter;
import com.trustledger.core.wallet.AmlFreezeOutcome;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.events.AccountFreezeRequestedV1;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AccountFreezeRequestedListenerTest {

    private final WalletApi wallets = mock(WalletApi.class);
    private final AccountFreezeRequestedListener listener = new AccountFreezeRequestedListener(wallets);
    private final AccountFreezeRequestedV1 event = new AccountFreezeRequestedV1(UUID.randomUUID(),
            Instant.parse("2026-11-22T10:00:00Z"), UUID.randomUUID(), UUID.randomUUID(), "R2");

    @Test
    void handsTheRequestToTheWalletApiWithTheCorrelationIdInTheMdc() {
        AtomicReference<String> seen = new AtomicReference<>();
        when(wallets.freezeForAml(event.eventId(), event.walletId(), event.caseId(), "R2")).thenAnswer(call -> {
            seen.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            return AmlFreezeOutcome.FROZEN;
        });

        listener.onFreezeRequested(record("aml-corr-7"));

        verify(wallets).freezeForAml(event.eventId(), event.walletId(), event.caseId(), "R2");
        assertThat(seen.get()).isEqualTo("aml-corr-7");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).as("removed afterwards").isNull();
    }

    @Test
    void anUnsafeOrMissingCorrelationIdIsIgnored() {
        assertThat(AccountFreezeRequestedListener.correlationId(record("line\nbreak"))).isNull();
        assertThat(AccountFreezeRequestedListener.correlationId(record("<script>"))).isNull();
        assertThat(AccountFreezeRequestedListener.correlationId(record(null))).isNull();
        assertThat(AccountFreezeRequestedListener.correlationId(record("abc-123_X.y"))).isEqualTo("abc-123_X.y");
    }

    private ConsumerRecord<String, AccountFreezeRequestedV1> record(String correlationId) {
        ConsumerRecord<String, AccountFreezeRequestedV1> record = new ConsumerRecord<>(AccountFreezeRequestedV1.TOPIC,
                0, 0L, event.walletId().toString(), event);
        if (correlationId != null) {
            record.headers().add("correlationId", correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
