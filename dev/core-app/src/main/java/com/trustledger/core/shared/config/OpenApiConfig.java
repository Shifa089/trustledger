package com.trustledger.core.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document (/v3/api-docs, Swagger UI at /swagger-ui.html). Every operation requires a Keycloak
 * bearer token unless it opts out (public endpoints such as the Razorpay webhook, from M7).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI trustLedgerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("TrustLedger Core API").version("v1")
                        .description("Regulated digital wallet: KYC, wallets, ledger, transfers, top-ups."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
