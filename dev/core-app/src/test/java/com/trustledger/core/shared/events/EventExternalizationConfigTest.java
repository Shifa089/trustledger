package com.trustledger.core.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.ExternalizedEvent;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import com.trustledger.events.WalletFrozenV1;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.messaging.Message;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.Externalized;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.modulith.events.support.BrokerRouting;

/**
 * What leaves core-app for Kafka (M8, ADR 0005 part B): selection, topic, key, payload and headers, for each of the
 * three events core-app produces. The Kafka round trip itself is in {@code EventExternalizationIT}.
 */
class EventExternalizationConfigTest {

    private static final UUID SENDER = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID RECEIVER = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");
    private static final UUID REF = UUID.fromString("0190a1b2-0000-7000-8000-0000000000ff");
    private static final UUID USER = UUID.fromString("0190a1b2-0000-7000-8000-0000000000aa");
    private static final UUID EVENT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
    private static final Instant AT = Instant.parse("2026-11-21T10:15:30Z");
    private static final EventMetadata METADATA = new EventMetadata(EVENT_ID, "corr-42");

    private final EventExternalizationConfiguration config =
            new EventExternalizationConfig().eventExternalizationConfiguration();

    private final TransferCompleted transfer = new TransferCompleted(REF, SENDER, RECEIVER, Money.parse("250.00"),
            REF, AT, METADATA);
    private final TopupCaptured topup = new TopupCaptured(REF, RECEIVER, Money.parse("500.00"), REF, AT, METADATA);
    private final WalletFrozen frozen = new WalletFrozen(SENDER, USER, FreezeSource.AML, AT, METADATA);

    private String keyOf(Object event) {
        RoutingTarget target = config.determineTarget(event);
        return BrokerRouting.of(target, new StandardEvaluationContext()).getKey(event);
    }

    @SuppressWarnings("unchecked")
    private Message<Object> messageOf(Object event) {
        return (Message<Object>) config.map(event);
    }

    private static String header(Message<?> message, String name) {
        return new String((byte[]) message.getHeaders().get(name), StandardCharsets.UTF_8);
    }

    @Test
    void transferCompletedGoesToItsTopicKeyedBySender() {
        assertThat(config.supports(transfer)).isTrue();
        assertThat(config.determineTarget(transfer).getTarget()).isEqualTo(TransferCompletedV1.TOPIC);
        assertThat(keyOf(transfer)).isEqualTo(SENDER.toString());
        assertThat(messageOf(transfer).getPayload()).isEqualTo(transfer.toV1());
    }

    @Test
    void topupCapturedGoesToItsTopicKeyedByWallet() {
        assertThat(config.supports(topup)).isTrue();
        assertThat(config.determineTarget(topup).getTarget()).isEqualTo(TopupCapturedV1.TOPIC);
        assertThat(keyOf(topup)).isEqualTo(RECEIVER.toString());
        assertThat(messageOf(topup).getPayload()).isEqualTo(topup.toV1());
    }

    @Test
    void walletFrozenGoesToItsTopicKeyedByWalletWithoutTheUserId() {
        assertThat(config.supports(frozen)).isTrue();
        assertThat(config.determineTarget(frozen).getTarget()).isEqualTo(WalletFrozenV1.TOPIC);
        assertThat(keyOf(frozen)).isEqualTo(SENDER.toString());
        assertThat(messageOf(frozen).getPayload()).isEqualTo(frozen.toV1()).isInstanceOf(WalletFrozenV1.class);
    }

    @Test
    void headersCarryTheEventIdAndCorrelationIdAsUtf8() {
        Message<Object> message = messageOf(transfer);

        assertThat(header(message, EventExternalizationConfig.EVENT_ID_HEADER)).isEqualTo(EVENT_ID.toString());
        assertThat(header(message, EventExternalizationConfig.CORRELATION_ID_HEADER)).isEqualTo("corr-42");
    }

    @Test
    void noCorrelationIdHeaderOutsideARequest() {
        TransferCompleted fromJob = new TransferCompleted(REF, SENDER, RECEIVER, Money.parse("1.00"), REF, AT,
                new EventMetadata(EVENT_ID, null));

        Message<Object> message = messageOf(fromJob);

        assertThat(message.getHeaders()).doesNotContainKey(EventExternalizationConfig.CORRELATION_ID_HEADER);
        assertThat(header(message, EventExternalizationConfig.EVENT_ID_HEADER)).isEqualTo(EVENT_ID.toString());
    }

    @Test
    void mappingIsStableForAResubmittedEvent() {
        assertThat(messageOf(transfer).getPayload()).isEqualTo(messageOf(transfer).getPayload());
        assertThat(header(messageOf(transfer), EventExternalizationConfig.EVENT_ID_HEADER))
                .isEqualTo(header(messageOf(transfer), EventExternalizationConfig.EVENT_ID_HEADER));
    }

    @Test
    void eventsThatDontOptInStayInside() {
        assertThat(config.supports(new NotAnnotated(METADATA))).as("ExternalizedEvent without @Externalized").isFalse();
        assertThat(config.supports(new NoContract(SENDER))).as("@Externalized without a contract").isFalse();
        assertThat(config.supports("some string")).isFalse();
    }

    record NotAnnotated(EventMetadata metadata) implements ExternalizedEvent {
        @Override
        public Record toV1() {
            return this;
        }
    }

    @Externalized("some.topic::#{#this.walletId()}")
    record NoContract(UUID walletId) {
    }
}
