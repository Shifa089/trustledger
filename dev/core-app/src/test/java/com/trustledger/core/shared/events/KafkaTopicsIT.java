package com.trustledger.core.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/** The contract topics exist at startup with 3 partitions and replication 1 (local and test, M8). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaTopicsIT {

    @Autowired
    KafkaContainer kafka;

    @Test
    void allFourContractTopicsAreCreated() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            Map<String, TopicDescription> topics = admin.describeTopics(KafkaTopicsConfig.TOPICS)
                    .allTopicNames().get();

            assertThat(topics).containsOnlyKeys(KafkaTopicsConfig.TOPICS);
            assertThat(topics.values()).allSatisfy(topic -> {
                assertThat(topic.partitions()).hasSize(3);
                assertThat(topic.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
            });
        }
    }

    @Test
    void theDeadLetterTopicOfEachConsumedTopicIsCreated() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            assertThat(admin.describeTopics(KafkaTopicsConfig.DEAD_LETTER_TOPICS).allTopicNames().get())
                    .containsOnlyKeys("trustledger.aml.account-freeze-requested.v1.DLT");
        }
    }
}
