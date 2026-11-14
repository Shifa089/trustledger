package com.trustledger.core.shared.web;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.Ordered;

/**
 * Order of the servlet filters, in one place. Lower runs first.
 * <ol>
 * <li>{@link #CORRELATION_ID}: every response, even a 401, carries {@code X-Correlation-Id}.</li>
 * <li>Spring Security ({@link #SECURITY}): authenticates the bearer token, enforces URL rules.</li>
 * <li>{@link #RATE_LIMIT}: needs the authenticated user; runs before anything touches the database.</li>
 * <li>{@link #USER_PROVISIONING}: just-in-time creation of the user row (identity module).</li>
 * </ol>
 * Filters after Spring Security still run inside its chain, so the security context is available.
 */
public final class FilterOrder {

    public static final int CORRELATION_ID = Ordered.HIGHEST_PRECEDENCE;
    public static final int SECURITY = SecurityFilterProperties.DEFAULT_FILTER_ORDER;
    public static final int RATE_LIMIT = SECURITY + 10;
    public static final int USER_PROVISIONING = SECURITY + 20;

    private FilterOrder() {
    }
}
