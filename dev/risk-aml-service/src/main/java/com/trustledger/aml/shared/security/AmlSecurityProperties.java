package com.trustledger.aml.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.security.*} of the AML service.
 *
 * @param swaggerPublic Swagger UI and {@code /v3/api-docs} without a token. Only the {@code local} profile sets it
 *                      (the same flag as core-app's); everywhere else they need a signed-in caller.
 */
@ConfigurationProperties("trustledger.security")
public record AmlSecurityProperties(boolean swaggerPublic) {
}
