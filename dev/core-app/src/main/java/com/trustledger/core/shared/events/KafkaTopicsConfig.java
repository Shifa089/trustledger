package com.trustledger.core.shared.events;

import com.trustledger.events.AccountFreezeRequestedV1;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import com.trustledger.events.WalletFrozenV1;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Creates the contract topics at startup, locally and in tests only ({@code trustledger.events.topics.create}).
 * In AWS the topics belong to the infrastructure, with production partition and replica counts.
 * <p>
 * All four contract topics, including {@link AccountFreezeRequestedV1#TOPIC}: risk-aml-service produces that one
 * (M9), and core-app consumes it, so it must exist locally whichever app starts first. Creating an existing topic
 * is a no-op.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("trustledger.events.topics.create")
public class KafkaTopicsConfig {

    /** Every contract topic, in one place (also used by tests). */
    public static final List<String> TOPICS = List.of(TransferCompletedV1.TOPIC, TopupCapturedV1.TOPIC,
            WalletFrozenV1.TOPIC, AccountFreezeRequestedV1.TOPIC);

    @Bean
    KafkaAdmin.NewTopics contractTopics(EventsProperties properties) {
        EventsProperties.Topics topics = properties.topics();
        return new KafkaAdmin.NewTopics(TOPICS.stream()
                .map(name -> TopicBuilder.name(name).partitions(topics.partitions()).replicas(topics.replicas())
                        .build())
                .toArray(NewTopic[]::new));
    }
}
