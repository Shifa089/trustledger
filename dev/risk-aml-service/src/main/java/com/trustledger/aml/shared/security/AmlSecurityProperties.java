package com.trustledger.aml.shared.security;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.security.*} of the AML service. Validated in the constructor so a bad value stops startup.
 *
 * @param cors          CORS allowlist for {@code /api/**} (the web app's compliance screens, Day 29 F1)
 * @param swaggerPublic Swagger UI and {@code /v3/api-docs} without a token. Only the {@code local} profile sets it
 *                      (the same flag as core-app's); everywhere else they need a signed-in caller.
 */
@ConfigurationProperties("trustledger.security")
public record AmlSecurityProperties(Cors cors, boolean swaggerPublic) {

    public AmlSecurityProperties {
        cors = cors == null ? new Cors(List.of()) : cors;
    }

    /**
     * Same rules as core-app's {@code SecurityProperties.Cors}.
     *
     * @param allowedOrigins exact origins such as {@code https://app.example.com}. No wildcards, no paths. Empty
     *                       means browsers on other origins cannot call the API.
     */
    public record Cors(List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            allowedOrigins.forEach(Cors::requireOrigin);
        }

        private static void requireOrigin(String origin) {
            if (origin == null || origin.isBlank() || origin.contains("*")) {
                throw new IllegalArgumentException(
                        "trustledger.security.cors.allowed-origins must list exact origins, no wildcards");
            }
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid CORS origin: " + origin, e);
            }
            boolean httpScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            boolean bare = uri.getHost() != null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
            if (!httpScheme || !bare) {
                throw new IllegalArgumentException(
                        "CORS origin must be scheme://host[:port] without a path: " + origin);
            }
        }
    }
}
