package com.trustledger.core.shared.demo;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Demo data (M16), only with the Spring profile {@code demo}: after startup (Flyway has run) the seeder writes
 * customer1's and customer2's FULL wallets. Never active in any other profile.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@EnableConfigurationProperties(DemoSeedProperties.class)
class DemoSeedConfig {

    @Bean
    DemoSeeder demoSeeder(JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock) {
        return new DemoSeeder(jdbc, transactions, clock);
    }

    @Bean
    ApplicationRunner demoSeedRunner(DemoSeeder seeder, DemoSeedProperties properties) {
        return args -> seeder.seed(properties);
    }
}
