package com.trustledger.core;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Reads one topic from the beginning, as a plain Kafka client (no Spring), the way a consumer outside core-app
 * would. Partitions are assigned directly, without a consumer group, so it starts reading at once.
 * <p>
 * Topics are shared by every test in a context, so tests look only at records with their own key (a fresh wallet).
 */
public final class KafkaTestConsumer implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    public KafkaTestConsumer(String bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"),
                new StringDeserializer(), new StringDeserializer());
        List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
    }

    /**
     * Polls until {@code expected} records with this key arrived (or {@code timeout} passed), then keeps polling a
     * little longer so an unexpected extra record (a duplicate) would show up too.
     */
    public List<ConsumerRecord<String, String>> recordsWithKey(String key, int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (withKey(key).size() < expected && System.nanoTime() < deadline) {
            poll(Duration.ofMillis(200));
        }
        long settle = System.nanoTime() + Duration.ofMillis(750).toNanos();
        while (System.nanoTime() < settle) {
            poll(Duration.ofMillis(150));
        }
        return withKey(key);
    }

    public static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private void poll(Duration duration) {
        consumer.poll(duration).forEach(seen::add);
    }

    private List<ConsumerRecord<String, String>> withKey(String key) {
        return seen.stream().filter(record -> key.equals(record.key())).toList();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
