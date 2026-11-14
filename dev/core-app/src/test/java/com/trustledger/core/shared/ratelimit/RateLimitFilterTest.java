package com.trustledger.core.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.shared.ratelimit.RateLimitFilter.Plan;
import com.trustledger.core.shared.ratelimit.RateLimiter.Decision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Bucket selection, keys, headers and fail-open, with a fake limiter (Redis is covered by RateLimitIT). */
class RateLimitFilterTest {

    private final List<String> keys = new ArrayList<>();
    private final List<Integer> limits = new ArrayList<>();
    private Decision next = Decision.allow(19);
    private RuntimeException failure;

    private final RateLimiter fake = (key, perMinute) -> {
        keys.add(key);
        limits.add(perMinute);
        if (failure != null) {
            throw failure;
        }
        return next;
    };

    private final RateLimitFilter filter = new RateLimitFilter(fake, new RateLimitProperties(true, 100, 20,
            List.of("POST /api/*/transfers", "POST /api/*/topups"), Duration.ofMillis(500)), JsonMapper.builder().build());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "POST, /api/v1/transfers,       MONEY",
            "POST, /api/v1/topups,          MONEY",
            "POST, /api/v2/transfers,       MONEY",
            "GET,  /api/v1/transfers,       DEFAULT",
            "GET,  /api/v1/transfers/123,   DEFAULT",
            "POST, /api/v1/transfers/x/approve, DEFAULT",
            "GET,  /api/v1/me,              DEFAULT",
            "POST, /api/v1/me/consents,     DEFAULT"
    })
    void choosesTheBucketByMethodAndPath(String method, String path, Plan expected) {
        assertThat(filter.planFor(method, path)).isEqualTo(expected);
    }

    @Test
    void moneyRequestUsesMoneyBucketKeyedBySubject() throws Exception {
        authenticate("sub-1");
        MockHttpServletResponse response = run("POST", "/api/v1/transfers");

        assertThat(keys).containsExactly("rl:money:sub-1");
        assertThat(limits).containsExactly(20);
        assertThat(response.getHeader(RateLimitFilter.REMAINING_HEADER)).isEqualTo("19");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void otherRequestUsesDefaultBucket() throws Exception {
        authenticate("sub-1");
        run("GET", "/api/v1/me");

        assertThat(keys).containsExactly("rl:default:sub-1");
        assertThat(limits).containsExactly(100);
    }

    @Test
    void emptyBucketGives429ProblemDetailWithRetryAfter() throws Exception {
        authenticate("sub-1");
        next = Decision.reject(Duration.ofMillis(2_100));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run("POST", "/api/v1/transfers", chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3"); // rounded up
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"errorCode\":\"RATE_LIMITED\"");
        assertThat(chain.getRequest()).as("request must not reach the controller").isNull();
    }

    @Test
    void redisFailureFailsOpen() throws Exception {
        authenticate("sub-1");
        failure = new IllegalStateException("connection refused");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run("POST", "/api/v1/transfers", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void anonymousRequestIsNotLimited() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        run("POST", "/api/v1/webhooks/razorpay");

        assertThat(keys).isEmpty();
    }

    @Test
    void nonApiPathIsNotLimited() throws Exception {
        authenticate("sub-1");
        run("GET", "/actuator/health");

        assertThat(keys).isEmpty();
    }

    @Test
    void disabledFilterDoesNothing() throws Exception {
        RateLimitFilter disabled = new RateLimitFilter(fake,
                new RateLimitProperties(false, 100, 20, List.of(), Duration.ofMillis(500)), JsonMapper.builder().build());
        authenticate("sub-1");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        disabled.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(keys).isEmpty();
    }

    private static void authenticate(String subject) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                subject, null, AuthorityUtils.createAuthorityList("ROLE_CUSTOMER")));
    }

    private MockHttpServletResponse run(String method, String path) throws Exception {
        return run(method, path, new MockFilterChain());
    }

    private MockHttpServletResponse run(String method, String path, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
