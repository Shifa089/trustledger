package com.trustledger.aml.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document of the AML service ({@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}; ADR 0010, the
 * same setup as core-app's). Every operation needs a Keycloak bearer token: the service has no public endpoint.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI amlOpenApi() {
        return new OpenAPI()
                .info(new Info().title("TrustLedger Risk and AML API").version("v1")
                        .description("AML alerts and case workflow for compliance officers (SPEC §5, FR-AML-4)."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
