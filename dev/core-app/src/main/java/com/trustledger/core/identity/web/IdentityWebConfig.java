package com.trustledger.core.identity.web;

import com.trustledger.core.identity.application.UserProvisioningService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class IdentityWebConfig {

    @Bean
    JitProvisioningFilter jitProvisioningFilter(UserProvisioningService provisioning, JsonMapper jsonMapper) {
        return new JitProvisioningFilter(provisioning, jsonMapper);
    }
}
