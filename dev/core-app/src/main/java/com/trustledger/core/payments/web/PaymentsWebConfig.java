package com.trustledger.core.payments.web;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.payments.application.IdempotencyService;
import com.trustledger.core.payments.application.PaymentsProperties;
import com.trustledger.core.payments.application.RequestHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the idempotency filter (a servlet filter bean) and interceptor for {@link Idempotent} endpoints.
 * <p>
 * A plain {@code @Configuration} that <em>declares</em> the {@link WebMvcConfigurer}, instead of being one: a
 * {@code @WebMvcTest} slice scans {@code WebMvcConfigurer} classes but not configuration classes, so slices of other
 * modules don't need the payments services (same pattern as {@code IdentityWebConfig}).
 */
@Configuration(proxyBeanMethods = false)
class PaymentsWebConfig {

    @Bean
    IdempotencyBodyFilter idempotencyBodyFilter(PaymentsProperties properties) {
        return new IdempotencyBodyFilter(properties);
    }

    @Bean
    WebMvcConfigurer idempotencyInterceptorConfigurer(IdempotencyService idempotency, RequestHasher hasher,
            IdentityApi identity) {
        IdempotencyInterceptor interceptor = new IdempotencyInterceptor(idempotency, hasher, identity);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
