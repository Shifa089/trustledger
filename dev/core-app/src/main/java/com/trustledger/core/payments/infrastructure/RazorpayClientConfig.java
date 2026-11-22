package com.trustledger.core.payments.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Razorpay {@link com.trustledger.core.payments.domain.PaymentGateway}. Nothing connects at startup, so the
 * app starts even when Razorpay is unreachable; the first call finds out. Its circuit-breaker state is a metric
 * ({@link CircuitBreakerStateGauge}, M12).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RazorpayProperties.class)
class RazorpayClientConfig {

    @Bean(destroyMethod = "close")
    RazorpayGatewayAdapter razorpayGateway(RazorpayProperties properties, MeterRegistry meters) {
        RazorpayGatewayAdapter adapter = RazorpayGatewayAdapter.create(properties);
        adapter.registerMetrics(meters);
        return adapter;
    }
}
