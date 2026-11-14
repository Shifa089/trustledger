package com.trustledger.core.shared.ratelimit;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.ratelimit.RateLimitProperties.Endpoint;
import com.trustledger.core.shared.ratelimit.RateLimiter.Decision;
import com.trustledger.core.shared.web.FilterOrder;
import com.trustledger.core.shared.web.ProblemDetailWriter;
import com.trustledger.core.shared.web.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-user rate limiting (SPEC M2): authenticated {@code /api/**} requests take a token from the user's
 * bucket; money endpoints use a separate, smaller bucket. An empty bucket gives 429 {@code RATE_LIMITED}
 * with {@code Retry-After}. Every allowed response carries {@code X-RateLimit-Remaining}.
 * <p>
 * Runs after Spring Security, so the user is known and unauthenticated requests were already rejected
 * without spending anyone's budget. Anonymous requests (webhooks, health) are not limited here.
 * The key is the token subject, not the IP: many users can share one IP (mobile carrier NAT).
 * <p>
 * Fail open: if Redis is unreachable, the request is allowed and a warning is logged. Rate limiting
 * protects capacity; money correctness is protected by idempotency keys and row locks, not by this filter.
 */
public class RateLimitFilter extends OncePerRequestFilter implements Ordered {

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String API_ROOT = "/api/";

    enum Plan { MONEY, DEFAULT }

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final List<Endpoint> moneyEndpoints;
    private final ProblemDetailWriter writer;

    RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties, JsonMapper jsonMapper) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.moneyEndpoints = properties.parsedMoneyEndpoints();
        this.writer = new ProblemDetailWriter(jsonMapper);
    }

    @Override
    public int getOrder() {
        return FilterOrder.RATE_LIMIT;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !path(request).startsWith(API_ROOT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        Plan plan = planFor(request.getMethod(), path(request));
        int perMinute = plan == Plan.MONEY ? properties.moneyPerMinute() : properties.defaultPerMinute();
        String key = "rl:" + plan.name().toLowerCase(Locale.ROOT) + ":" + auth.getName();

        Decision decision;
        try {
            decision = rateLimiter.tryConsume(key, perMinute);
        } catch (RuntimeException e) {
            // Fail open. The exception type only: messages could include connection details.
            log.warn("Rate limiter unavailable, request allowed without limiting: {}", e.getClass().getSimpleName());
            chain.doFilter(request, response);
            return;
        }

        if (!decision.allowed()) {
            long seconds = Math.max(1, (long) Math.ceil(decision.retryAfter().toMillis() / 1000.0));
            log.info("Rate limit exceeded for plan {}", plan);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            response.setHeader(REMAINING_HEADER, "0");
            ProblemDetail problem = ProblemDetails.of(ErrorCode.RATE_LIMITED,
                    "Too many requests. Retry after " + seconds + " seconds.", request.getRequestURI());
            writer.write(response, problem);
            return;
        }
        response.setHeader(REMAINING_HEADER, Long.toString(decision.remaining()));
        chain.doFilter(request, response);
    }

    /** Which bucket a request uses. Package-private for unit tests. */
    Plan planFor(String method, String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (Endpoint endpoint : moneyEndpoints) {
            if (endpoint.method().matches(method) && endpoint.pattern().matches(container)) {
                return Plan.MONEY;
            }
        }
        return Plan.DEFAULT;
    }

    private static String path(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
