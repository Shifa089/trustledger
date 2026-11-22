package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.application.CaseProperties;
import com.trustledger.aml.cases.application.FreezeRelayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the case workflow's and the freeze relay's settings (FR-AML-3/4). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CaseProperties.class, FreezeRelayProperties.class})
public class CasesConfig {
}
