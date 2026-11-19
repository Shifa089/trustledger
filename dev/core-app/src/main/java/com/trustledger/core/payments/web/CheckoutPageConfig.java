package com.trustledger.core.payments.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The manual-test page for Razorpay Checkout in test mode (M7), {@code /checkout.html} and its script. Only when
 * {@code trustledger.payments.topups.checkout-page=true}, which only {@code application-local.yml} sets. The files
 * live in {@code classpath:/checkout/}, not {@code /static}, so no other profile ever serves them.
 * <p>
 * Its own security chain, because the API chain denies every non-API path and its CSP forbids scripts. This chain
 * matches the two files only, needs no token (the page asks for one and sends it as a bearer token to the API, same
 * origin), and allows Razorpay's checkout script and frames. The page has no inline script, so no
 * {@code 'unsafe-inline'} for scripts.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "trustledger.payments.topups.checkout-page", havingValue = "true")
class CheckoutPageConfig {

    static final String[] PATHS = {"/checkout.html", "/checkout.js"};

    static final String CSP = "default-src 'self'; "
            + "script-src 'self' https://checkout.razorpay.com https://*.razorpay.com; "
            + "frame-src https://api.razorpay.com https://*.razorpay.com; "
            + "connect-src 'self' https://*.razorpay.com; "
            + "img-src 'self' data: https://*.razorpay.com; "
            + "style-src 'self' 'unsafe-inline'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Bean
    @Order(2)
    SecurityFilterChain checkoutPageSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(PATHS)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    @Bean
    WebMvcConfigurer checkoutPageResources() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler(PATHS).addResourceLocations("classpath:/checkout/");
            }
        };
    }
}
