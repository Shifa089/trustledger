package com.trustledger.core.identity.web;

import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.web.FilterOrder;
import com.trustledger.core.shared.web.ProblemDetailWriter;
import com.trustledger.core.shared.web.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-ID-2: on every authenticated {@code /api/**} request, make sure the caller has a user row
 * (created on the first request), and put our user ID into the MDC as {@code userId} so every log line
 * of the request can be traced to a user without logging personal data (SPEC §6 "Logging").
 * <p>
 * Runs after Spring Security and rate limiting ({@link FilterOrder}). Costs one indexed SELECT per
 * request once the user exists. Registered in {@link IdentityWebConfig} (not as a {@code @Component}) so
 * {@code @WebMvcTest} slices, which pick up every filter component, do not need the identity services.
 */
public class JitProvisioningFilter extends OncePerRequestFilter implements Ordered {

    public static final String MDC_USER_ID = "userId";

    private static final Logger log = LoggerFactory.getLogger(JitProvisioningFilter.class);
    private static final String API_ROOT = "/api/";

    private final UserProvisioningService provisioning;
    private final ProblemDetailWriter writer;

    public JitProvisioningFilter(UserProvisioningService provisioning, JsonMapper jsonMapper) {
        this.provisioning = provisioning;
        this.writer = new ProblemDetailWriter(jsonMapper);
    }

    @Override
    public int getOrder() {
        return FilterOrder.USER_PROVISIONING;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith(API_ROOT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId;
        try {
            userId = provisioning.ensureUser(TokenClaims.identity(token.getToken()));
        } catch (RuntimeException e) {
            // Filters run outside Spring MVC, so the global handler would not format this error.
            log.error("User provisioning failed", e);
            writer.write(response, ProblemDetails.of(ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred. Quote the correlationId when contacting support.",
                    request.getRequestURI()));
            return;
        }
        MDC.put(MDC_USER_ID, userId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_ID);
        }
    }
}
