package com.trustledger.aml.rules.infrastructure;

import com.trustledger.aml.rules.domain.AmlProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds and checks the rule thresholds at startup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AmlProperties.class)
class RulesConfig {
}
