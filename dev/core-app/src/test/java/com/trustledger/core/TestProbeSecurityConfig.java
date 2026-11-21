package com.trustledger.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test sources only, never shipped. Since Day 14 B the production chain ends in {@code denyAll()} (an endpoint
 * without its own role rule is refused), so test-only probe endpoints need a rule of their own. This chain covers
 * {@code /api/*}{@code /test/**} only: any signed-in user, with the same JWT validation and role mapping as
 * production; the probes add {@code @PreAuthorize} where they test method security.
 * <p>
 * Paths outside {@code /test/} (for example {@code /customer/probe}) still go through the production chain, which
 * is how {@code SecurityMatrixIT} proves that an unlisted endpoint fails closed.
 */
@Configuration(proxyBeanMethods = false)
class TestProbeSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain testProbeSecurityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter)
            throws Exception {
        http
                .securityMatcher("/api/*/test/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }
}
