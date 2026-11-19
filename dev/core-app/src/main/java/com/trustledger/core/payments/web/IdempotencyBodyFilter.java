package com.trustledger.core.payments.web;

import com.trustledger.core.payments.application.PaymentsProperties;
import com.trustledger.core.shared.web.FilterOrder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * For requests with an {@code Idempotency-Key} header only: caches the request body ({@link CachedBodyRequest})
 * and the response body ({@link ContentCachingResponseWrapper}), so {@link IdempotencyInterceptor} can hash the
 * one and store the other. Runs after JIT provisioning ({@link FilterOrder#IDEMPOTENCY_BODY}).
 * <p>
 * The interceptor makes the decisions; this filter only makes the bodies readable twice.
 */
public class IdempotencyBodyFilter extends OncePerRequestFilter implements Ordered {

    private final int maxBodyBytes;

    public IdempotencyBodyFilter(PaymentsProperties properties) {
        this.maxBodyBytes = Math.toIntExact(properties.idempotency().maxBodySize().toBytes());
    }

    @Override
    public int getOrder() {
        return FilterOrder.IDEMPOTENCY_BODY;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(IdempotencyInterceptor.HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CachedBodyRequest(request, maxBodyBytes), cachingResponse);
        } finally {
            cachingResponse.copyBodyToResponse();
        }
    }
}
