package com.trustledger.core.shared.security;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless OAuth2 resource server (SPEC §6 "Security"). Keycloak issues the JWTs; the issuer
 * ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}) and audience ({@code ...jwt.audiences})
 * come from configuration, and {@link #jwtDecoder} checks signature, expiry, issuer and audience.
 * <p>
 * URL rules are a coarse first line; endpoints add {@code @PreAuthorize} (role per endpoint, ownership
 * checks against BOLA). Anything not listed is denied. Paths use {@code /api/*} so a future API version
 * inherits the same rules.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    static final String[] SWAGGER_PATHS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    /** API responses are JSON only: nothing may load, frame or run. */
    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private static final String ADMIN = Role.ADMIN.name();
    private static final String AUDITOR = Role.AUDITOR.name();
    private static final String OFFICER = Role.COMPLIANCE_OFFICER.name();
    private static final String CUSTOMER = Role.CUSTOMER.name();

    /**
     * Declared here instead of relying on Boot's conditional auto-configuration so that the checks are
     * visible in one place and a missing issuer fails startup. Keycloak's keys are fetched lazily on
     * the first request (via OIDC discovery), so the app can start before Keycloak is up.
     * Checks: RS256 signature against the issuer's JWKS, {@code exp}/{@code nbf} (60 s clock skew),
     * {@code iss} equals the configured issuer, {@code aud} contains one of the configured audiences.
     */
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        String issuer = properties.getJwt().getIssuerUri();
        List<String> audiences = List.copyOf(properties.getJwt().getAudiences());
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.issuer-uri is required");
        }
        if (audiences.isEmpty()) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.audiences is required");
        }
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer), audienceValidator(audiences)));
            return decoder;
        });
    }

    /** Rejects tokens issued for another client or API of the same realm. */
    static OAuth2TokenValidator<Jwt> audienceValidator(List<String> accepted) {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The required audience is missing", null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(accepted::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRoleConverter());
        converter.setPrincipalClaimName("sub"); // Keycloak user ID, never the e-mail or username
        return converter;
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter,
            JsonMapper jsonMapper) throws Exception {
        ProblemDetailSecurityHandler problems = new ProblemDetailSecurityHandler(jsonMapper);
        stateless(http);
        http
                .cors(cors -> { }) // uses the CorsConfigurationSource bean below
                .authorizeHttpRequests(auth -> auth
                        // Error dispatches only render an error for a request that was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Public: HMAC-verified by the webhook controller (FR-P-5), and health probes.
                        .requestMatchers("/api/*/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // More specific than /admin/** below, so it must come first (SPEC §5).
                        .requestMatchers(HttpMethod.GET, "/api/*/admin/ledger/accounts/*/balance")
                                .hasAnyRole(ADMIN, AUDITOR)
                        .requestMatchers("/api/*/compliance/**").hasRole(OFFICER)
                        .requestMatchers("/api/*/admin/**").hasRole(ADMIN)
                        .requestMatchers("/api/*/audit/**").hasRole(AUDITOR)
                        // Customer-only endpoints (SPEC §5). Checked here as well as by @PreAuthorize so a
                        // wrong role gets 403 before the body is parsed (otherwise a bad body gives 400).
                        .requestMatchers("/api/*/me/consents").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.POST, "/api/*/transfers", "/api/*/topups").hasRole(CUSTOMER)
                        .requestMatchers("/actuator/**").hasRole(ADMIN)
                        // Reached only when the local-only swagger chain does not exist.
                        .requestMatchers(SWAGGER_PATHS).authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds())));
        return http.build();
    }

    /**
     * Local profile only ({@code trustledger.security.swagger-public=true}): Swagger UI without a
     * token. A separate chain because the UI needs scripts and styles that the strict API CSP forbids.
     */
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "trustledger.security.swagger-public", havingValue = "true")
    SecurityFilterChain swaggerSecurityFilterChain(HttpSecurity http) throws Exception {
        stateless(http);
        http
                .securityMatcher(SWAGGER_PATHS)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));
        return http.build();
    }

    /** Only {@code /api/**}; origins from {@code trustledger.security.cors.allowed-origins}. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Location"));
        cors.setAllowCredentials(false); // bearer tokens, no cookies
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /**
     * No HTTP session, no cookies, so CSRF protection is not needed (a browser cannot attach the
     * bearer token to a forged cross-site request). No login form, no HTTP Basic: Keycloak does logins.
     */
    private static void stateless(HttpSecurity http) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .anonymous(anonymous -> { });
    }
}
