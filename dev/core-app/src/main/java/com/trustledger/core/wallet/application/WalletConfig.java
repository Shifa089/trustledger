package com.trustledger.core.wallet.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LimitsProperties.class, ReconciliationProperties.class})
class WalletConfig {
}
