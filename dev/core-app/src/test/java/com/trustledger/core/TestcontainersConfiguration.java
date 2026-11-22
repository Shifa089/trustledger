package com.trustledger.core;

import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers setup. Import it with {@code @Import(TestcontainersConfiguration.class)}.
 * Spring's test context cache reuses the same containers for every test class with the same
 * configuration, so they start once per test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Must match trustledger.kyc.storage.bucket in src/test/resources/config/application.yml. */
    public static final String KYC_BUCKET = "trustledger-kyc-test";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
    }

    /** Same broker image as infra/docker-compose.yml; the outbox externalizes to it in every IT (M8). */
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    }

    /**
     * SMTP sink for notification e-mails (FR-N-1); same image as infra/docker-compose.yml. Tests read what arrived
     * through its HTTP API ({@link MailpitClient}).
     */
    @Bean
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(DockerImageName.parse(MailpitClient.IMAGE))
                .withExposedPorts(MailpitClient.SMTP_PORT, MailpitClient.HTTP_PORT)
                .waitingFor(Wait.forHttp("/readyz").forPort(MailpitClient.HTTP_PORT));
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpitContainer) {
        return registry -> {
            registry.add("spring.mail.host", mailpitContainer::getHost);
            registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(MailpitClient.SMTP_PORT));
        };
    }

    @Bean
    MailpitClient mailpitClient(GenericContainer<?> mailpitContainer) {
        return new MailpitClient(mailpitContainer);
    }

    /** Real Keycloak with the project's realm; the app validates its tokens like in production. */
    @Bean
    KeycloakContainer keycloakContainer() {
        return new KeycloakContainer();
    }

    @Bean
    DynamicPropertyRegistrar keycloakProperties(KeycloakContainer keycloak) {
        return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                keycloak::issuerUri);
    }

    /**
     * S3 for KYC documents; same image as infra/docker-compose.yml. LocalStack skips presigned-URL signature
     * and expiry checks by default; turned on here so tests see what real S3 does with a tampered or expired link.
     */
    @Bean
    LocalStackContainer localStackContainer() {
        return new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14")).withServices("s3")
                .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0");
    }

    /**
     * Stands in for the Razorpay API (ADR 0014). In-process, not a container: it starts in milliseconds.
     * Tests stub it per case and call {@code resetAll()} afterwards, since every IT shares this one server.
     */
    @Bean(destroyMethod = "stop")
    WireMockServer razorpayWireMock() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar razorpayProperties(WireMockServer razorpayWireMock) {
        return registry -> registry.add("trustledger.razorpay.base-url", razorpayWireMock::baseUrl);
    }

    @Bean
    DynamicPropertyRegistrar localStackProperties(LocalStackContainer localStack) {
        return registry -> {
            registry.add("trustledger.kyc.storage.endpoint", () -> localStack.getEndpoint().toString());
            registry.add("trustledger.kyc.storage.region", localStack::getRegion);
            registry.add("trustledger.kyc.storage.access-key", localStack::getAccessKey);
            registry.add("trustledger.kyc.storage.secret-key", localStack::getSecretKey);
            // Data exports (FR-PR-2): same LocalStack, a bucket of their own.
            registry.add("trustledger.privacy.storage.endpoint", () -> localStack.getEndpoint().toString());
            registry.add("trustledger.privacy.storage.region", localStack::getRegion);
            registry.add("trustledger.privacy.storage.access-key", localStack::getAccessKey);
            registry.add("trustledger.privacy.storage.secret-key", localStack::getSecretKey);
        };
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
