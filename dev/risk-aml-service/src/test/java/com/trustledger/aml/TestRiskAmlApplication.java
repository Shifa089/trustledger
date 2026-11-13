package com.trustledger.aml;

import org.springframework.boot.SpringApplication;

/**
 * Runs risk-aml-service locally against throwaway Testcontainers instead of docker compose.
 * Start it from the IDE (test classpath).
 */
public class TestRiskAmlApplication {

    public static void main(String[] args) {
        SpringApplication.from(RiskAmlApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
