package com.trustledger.core.shared.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Security response headers (OWASP API8 security misconfiguration; M13). Every kind of response the API sends
 * carries them: success, 401, 403 from a role rule, 403 from {@code denyAll()}, 400 and 404 ProblemDetails from the
 * controllers, the public webhook and the public health probe. An error path that skipped the headers would be the
 * easiest one to miss, so each case asserts the full set.
 * <ul>
 * <li>CSP {@code default-src 'none'; frame-ancestors 'none'}: a JSON API loads, frames and runs nothing;</li>
 * <li>{@code X-Frame-Options: DENY} for browsers without {@code frame-ancestors};</li>
 * <li>{@code X-Content-Type-Options: nosniff}: JSON is never sniffed into HTML or script;</li>
 * <li>{@code Referrer-Policy: no-referrer}: IDs in our URLs never leak to another site;</li>
 * <li>{@code Cache-Control: no-store}: balances and personal data are never cached by a browser or proxy;</li>
 * <li>HSTS for one year with sub-domains, sent only over HTTPS (RFC 6797 tells browsers to ignore it on HTTP);</li>
 * <li>no {@code Set-Cookie}: the API is stateless.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SecurityHeadersIT {

    private static final String HSTS = "max-age=31536000 ; includeSubDomains";

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new CorrelationIdFilter())
                .apply(springSecurity()).build();
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            // success
            "GET,  /api/v1/ping,                                          CUSTOMER, 200",
            // no token -> 401 from the entry point
            "GET,  /api/v1/wallets/me,                                    NONE,     401",
            // wrong role -> 403 from a URL rule
            "GET,  /api/v1/wallets/me,                                    AUDITOR,  403",
            // no rule at all -> 403 from denyAll()
            "GET,  /internal/anything,                                    ADMIN,    403",
            // past security, rejected by the controller (no Idempotency-Key, no body) -> 400 ProblemDetail
            "POST, /api/v1/transfers,                                     CUSTOMER, 400",
            // past security, unknown resource (the caller is not provisioned) -> 404 ProblemDetail
            "GET,  /api/v1/transfers/00000000-0000-7000-8000-000000000001, CUSTOMER, 404",
            // public webhook without a signature -> 400
            "POST, /api/v1/webhooks/razorpay,                             NONE,     400",
            // public health probe
            "GET,  /actuator/health,                                      NONE,     200",
    })
    void everyResponseCarriesTheSecurityHeaders(String method, String path, String role, int expected)
            throws Exception {
        ResultActions result = mvc.perform(request(method, path, role).secure(true))
                .andExpect(status().is(expected));
        expectSecurityHeaders(result);
        result.andExpect(header().string("Strict-Transport-Security", HSTS));
    }

    @Test
    void hstsIsSentOnlyOverHttps() throws Exception {
        expectSecurityHeaders(mvc.perform(request("GET", "/api/v1/ping", "CUSTOMER").secure(false))
                .andExpect(status().isOk()))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    private static ResultActions expectSecurityHeaders(ResultActions result) throws Exception {
        return result
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private static MockHttpServletRequestBuilder request(String method, String path, String role) {
        MockHttpServletRequestBuilder request = "POST".equals(method) ? post(path) : get(path);
        return "NONE".equals(role) ? request : request.with(token(role));
    }

    /** A mock JWT as Keycloak would issue it, converted by the production {@link JwtRoleConverter}. */
    private static RequestPostProcessor token(String role) {
        return jwt()
                .jwt(j -> j.subject("user-" + role)
                        .claim("realm_access", Map.of("roles", List.of(role, "default-roles-trustledger"))))
                .authorities(new JwtRoleConverter());
    }
}
