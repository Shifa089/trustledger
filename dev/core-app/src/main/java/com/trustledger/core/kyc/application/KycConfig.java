package com.trustledger.core.kyc.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({KycProperties.class, KycDocumentProperties.class})
class KycConfig {
}
