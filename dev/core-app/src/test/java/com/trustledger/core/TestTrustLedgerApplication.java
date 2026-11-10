package com.trustledger.core;

import org.springframework.boot.SpringApplication;

/**
 * Runs core-app locally against throwaway Testcontainers instead of docker compose.
 * Start it from the IDE (test classpath).
 */
public class TestTrustLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.from(TrustLedgerApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
