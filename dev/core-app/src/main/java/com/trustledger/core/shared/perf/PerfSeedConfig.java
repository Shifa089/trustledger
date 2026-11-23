package com.trustledger.core.shared.perf;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Load-test data (M13), only with the Spring profile {@code perf}: after startup (Flyway has run) the seeder writes
 * the wallets of {@link PerfSeedProperties}. Never active in any other profile.
 */
@Configuration(proxyBeanMethods = false)
@Profile("perf")
@EnableConfigurationProperties(PerfSeedProperties.class)
class PerfSeedConfig {

    @Bean
    PerfSeeder perfSeeder(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        return new PerfSeeder(jdbc, transactions, clock);
    }

    @Bean
    ApplicationRunner perfSeedRunner(PerfSeeder seeder, PerfSeedProperties properties) {
        return args -> seeder.seed(properties.wallets(), properties.openingBalance());
    }
}
