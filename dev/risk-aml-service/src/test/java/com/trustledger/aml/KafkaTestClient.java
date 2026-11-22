package com.trustledger.aml;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends records in exactly the shape core-app's outbox writes them (JSON bytes of the contract, a
 * {@code __TypeId__} header naming the contract class, {@code eventId} and {@code correlationId} headers as UTF-8
 * bytes, the wallet ID as key), and reads dead-letter topics.
 */
public final class KafkaTestClient implements AutoCloseable {

    public static final String TYPE_ID_HEADER = "__TypeId__";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String bootstrapServers;
    private final KafkaProducer<String, byte[]> producer;

    public KafkaTestClient(KafkaContainer kafka) {
        this.bootstrapServers = kafka.getBootstrapServers();
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all"),
                new StringSerializer(), new ByteArraySerializer());
    }

    /** A contract record as core-app sends it. */
    public void sendContract(String topic, String key, Record contract, UUID eventId) {
        sendContract(topic, key, contract, eventId, Map.of());
    }

    /** A contract record as core-app sends it, plus extra headers (e.g. the W3C {@code traceparent}). */
    public void sendContract(String topic, String key, Record contract, UUID eventId, Map<String, String> headers) {
        send(topic, key, JSON.writeValueAsBytes(contract), contract.getClass().getName(), eventId, headers);
    }

    /** Raw bytes with the given type header: for records core-app would never send. */
    public void send(String topic, String key, byte[] value, String typeId, UUID eventId) {
        send(topic, key, value, typeId, eventId, Map.of());
    }

    private void send(String topic, String key, byte[] value, String typeId, UUID eventId,
            Map<String, String> headers) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
        record.headers().add(TYPE_ID_HEADER, typeId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        headers.forEach((name, headerValue) -> record.headers().add(name, headerValue.getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send the test record", e);
        }
    }

    public static byte[] json(Object value) {
        return JSON.writeValueAsBytes(value);
    }

    /**
     * Reads {@code topic} from the beginning until a record with {@code key} arrives, then keeps reading for
     * {@code settle} to catch copies. Returns every record with that key.
     */
    public List<ConsumerRecord<String, byte[]>> readByKey(String topic, String key, Duration timeout,
            Duration settle) {
        return read(topic, record -> key.equals(record.key()), timeout, settle);
    }

    /** Like {@link #readByKey}, but returns as soon as the timeout passes, even with nothing found. */
    public List<ConsumerRecord<String, byte[]>> read(String topic, Predicate<ConsumerRecord<String, byte[]>> match,
            Duration timeout, Duration settle) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            long settleUntil = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(deadline, settleUntil)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (match.test(record)) {
                        found.add(record);
                        settleUntil = Math.min(settleUntil, System.nanoTime() + settle.toNanos());
                    }
                }
            }
        }
        return found;
    }

    public static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        producer.close();
    }
}
