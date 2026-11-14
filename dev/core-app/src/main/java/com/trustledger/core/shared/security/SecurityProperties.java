package com.trustledger.core.shared.security;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.security.*}. Validated in the constructor so a bad value stops startup.
 *
 * @param cors          CORS allowlist for {@code /api/**}
 * @param swaggerPublic Swagger UI and {@code /v3/api-docs} without a token. Only the {@code local}
 *                      profile sets it; everywhere else they need authentication.
 */
@ConfigurationProperties("trustledger.security")
public record SecurityProperties(Cors cors, boolean swaggerPublic) {

    public SecurityProperties {
        cors = cors == null ? new Cors(List.of()) : cors;
    }

    /**
     * @param allowedOrigins exact origins such as {@code https://app.example.com}. No wildcards, no
     *                       paths. Empty means browsers on other origins cannot call the API.
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
