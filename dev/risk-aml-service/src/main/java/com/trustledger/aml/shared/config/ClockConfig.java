package com.trustledger.aml.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one source of "now" (CLAUDE.md: inject Clock), so tests can fix time. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
