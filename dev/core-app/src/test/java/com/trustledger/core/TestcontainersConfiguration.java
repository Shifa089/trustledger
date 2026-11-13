package com.trustledger.core;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers setup. Import it with {@code @Import(TestcontainersConfiguration.class)}.
 * Spring's test context cache reuses the same containers for every test class with the same
 * configuration, so they start once per test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

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
}
