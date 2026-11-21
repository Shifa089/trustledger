package com.trustledger.aml.shared.security;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless OAuth2 resource server, the same as core-app's (SPEC §6 "Security", M9 "same Keycloak resource-server
 * security"): same realm, same issuer and audience checks, same role mapping ({@link JwtRoleConverter}, a copy).
 * <p>
 * The AML API (SPEC §5) is for compliance officers only. Health is public for probes; the rest of actuator needs
 * ADMIN; anything not listed is denied, so a new endpoint needs its own rule here.
 * <p>
 * No CORS: this service has no browser client.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * Checks: RS256 signature against the issuer's JWKS, {@code exp}/{@code nbf}, {@code iss} equals the configured
     * issuer, {@code aud} contains one of the configured audiences. The keys are fetched lazily on the first token,
     * so the service starts before Keycloak is up; a missing issuer or audience fails startup.
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
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable) // bearer tokens only, no cookies
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/*/aml/**").hasRole(Role.COMPLIANCE_OFFICER.name())
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
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
}
