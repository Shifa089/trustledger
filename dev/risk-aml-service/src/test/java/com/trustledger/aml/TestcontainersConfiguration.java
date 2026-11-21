package com.trustledger.aml;

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
}
