package com.trustledger.aml.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs of this service (today only the freeze-request relay). {@code trustledger.aml.scheduling.enabled}
 * turns them all off, for tests that run a job by hand.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "trustledger.aml.scheduling.enabled", matchIfMissing = true)
public class SchedulingConfig {
}
