package com.trustledger.core.payments.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentsProperties.class, TopupReconcilerProperties.class})
class PaymentsConfig {
}
