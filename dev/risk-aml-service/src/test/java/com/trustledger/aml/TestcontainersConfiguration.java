package com.trustledger.aml;

import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers setup. Import it with {@code @Import(TestcontainersConfiguration.class)}.
 * The AML service doesn't use Redis or Keycloak here (see the test application.yml), so PostgreSQL and Kafka only.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

    /** Same broker image as infra/docker-compose.yml and core-app's tests. */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    }

    /**
     * Spans and log records in memory (M12, ADR 0020): tests call {@code start()} and assert on what the OTLP
     * collector would receive. Boot adds both exporters to its OpenTelemetry SDK.
     */
    @Bean
    TestTelemetry testTelemetry() {
        return new TestTelemetry();
    }

    @Bean
    SpanExporter testSpanExporter(TestTelemetry testTelemetry) {
        return testTelemetry.spanExporter();
    }

    @Bean
    LogRecordExporter testLogRecordExporter(TestTelemetry testTelemetry) {
        return testTelemetry.logRecordExporter();
    }
}
