package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Security matrix (SPEC §9 must-pass #11): 401 without a token, 403 with the wrong role, 200 with the
 * right one, for every path group. Tokens are mock JWTs carrying a Keycloak-style
 * {@code realm_access.roles} claim, run through the real {@link JwtRoleConverter}. Real Keycloak tokens
 * are covered by {@code KeycloakRealmIT}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SecurityMatrixIT {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Hand-built MockMvc only adds the filters it is given: the correlation filter first, then security.
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new CorrelationIdFilter())
                .apply(springSecurity()).build();
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            // path group                                 role                expected
            "GET, /api/v1/compliance/probe,               NONE,               401",
            "GET, /api/v1/compliance/probe,               CUSTOMER,           403",
            "GET, /api/v1/compliance/probe,               COMPLIANCE_OFFICER, 200",
            "GET, /api/v1/compliance/probe,               ADMIN,              403",
            "GET, /api/v1/compliance/probe,               AUDITOR,            403",
            "GET, /api/v1/admin/probe,                    NONE,               401",
            "GET, /api/v1/admin/probe,                    CUSTOMER,           403",
            "GET, /api/v1/admin/probe,                    COMPLIANCE_OFFICER, 403",
            "GET, /api/v1/admin/probe,                    ADMIN,              200",
            "GET, /api/v1/admin/probe,                    AUDITOR,            403",
            "GET, /api/v1/admin/ledger/accounts/CASH/balance, NONE,           401",
            "GET, /api/v1/admin/ledger/accounts/CASH/balance, CUSTOMER,       403",
            "GET, /api/v1/admin/ledger/accounts/CASH/balance, COMPLIANCE_OFFICER, 403",
            "GET, /api/v1/admin/ledger/accounts/CASH/balance, ADMIN,          200",
            "GET, /api/v1/admin/ledger/accounts/CASH/balance, AUDITOR,        200",
            "GET, /api/v1/audit/probe,                    NONE,               401",
            "GET, /api/v1/audit/probe,                    CUSTOMER,           403",
            "GET, /api/v1/audit/probe,                    COMPLIANCE_OFFICER, 403",
            "GET, /api/v1/audit/probe,                    ADMIN,              403",
            "GET, /api/v1/audit/probe,                    AUDITOR,            200",
            "GET, /api/v1/customer/probe,                 NONE,               401",
            "GET, /api/v1/customer/probe,                 NO_ROLES,           200",
            "GET, /api/v1/customer/probe,                 CUSTOMER,           200",
            "GET, /api/v1/customer/probe,                 COMPLIANCE_OFFICER, 200",
            "GET, /api/v1/customer/probe,                 ADMIN,              200",
            "GET, /api/v1/customer/probe,                 AUDITOR,            200",
            "GET, /api/v1/customer/method-secured,        CUSTOMER,           403",
            "GET, /api/v1/customer/method-secured,        AUDITOR,            403",
            "GET, /api/v1/customer/method-secured,        ADMIN,              200",
            "GET, /api/v1/me,                             NONE,               401",
            "GET, /api/v1/me,                             NO_ROLES,           200",
            "GET, /api/v1/me,                             CUSTOMER,           200",
            "GET, /api/v1/me,                             COMPLIANCE_OFFICER, 200",
            "GET, /api/v1/me,                             AUDITOR,            200",
            "GET, /api/v1/me/consents,                    NONE,               401",
            "GET, /api/v1/me/consents,                    CUSTOMER,           200",
            "GET, /api/v1/me/consents,                    COMPLIANCE_OFFICER, 403",
            "GET, /api/v1/me/consents,                    ADMIN,              403",
            "GET, /api/v1/me/consents,                    AUDITOR,            403",
            "POST, /api/v1/me/consents,                   COMPLIANCE_OFFICER, 403",
            "POST, /api/v1/transfers,                     NONE,               401",
            "POST, /api/v1/transfers,                     CUSTOMER,           501",
            "POST, /api/v1/transfers,                     ADMIN,              403",
            "POST, /api/v1/transfers,                     AUDITOR,            403",
            "POST, /api/v1/topups,                        NONE,               401",
            "POST, /api/v1/topups,                        CUSTOMER,           501",
            "POST, /api/v1/topups,                        COMPLIANCE_OFFICER, 403",
            "GET, /api/v1/ping,                           NONE,               401",
            "GET, /api/v1/ping,                           AUDITOR,            200",
            "POST, /api/v1/webhooks/probe,                NONE,               200",
            "GET, /actuator/health,                       NONE,               200",
            "GET, /actuator/env,                          NONE,               401",
            "GET, /actuator/env,                          CUSTOMER,           403",
            "GET, /v3/api-docs,                           NONE,               401",
            "GET, /v3/api-docs,                           CUSTOMER,           200",
            "GET, /ping,                                  NONE,               401",
            "GET, /ping,                                  ADMIN,              403",
            "GET, /internal/anything,                     ADMIN,              403",
    })
    void enforcesRoleMatrix(String method, String path, String role, int expected) throws Exception {
        MockHttpServletRequestBuilder request = "POST".equals(method) ? post(path) : get(path);
        if (!"NONE".equals(role)) {
            request.with(token(role));
        }
        mvc.perform(request).andExpect(status().is(expected));
    }

    @Test
    void unauthenticatedIsProblemDetailWithBearerChallenge() throws Exception {
        mvc.perform(get("/api/v1/admin/probe").header(CorrelationIdFilter.HEADER, "sec-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "sec-401"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/probe"))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("sec-401"));
    }

    @Test
    void invalidBearerTokenIsRejectedByTheRealDecoder() throws Exception {
        mvc.perform(get("/api/v1/ping").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
                // The decoder's message describes the token; it must not leak into the response.
                .andExpect(jsonPath("$.detail").value("A valid bearer token is required."));
    }

    @Test
    void wrongRoleIsProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/admin/probe").with(token("CUSTOMER")).header(CorrelationIdFilter.HEADER, "sec-403"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/access-denied"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.correlationId").value("sec-403"));
    }

    @Test
    void methodSecurityDenialIsTheSameProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/customer/method-secured").with(token("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void pingShowsSubjectAndMappedRolesOnly() throws Exception {
        mvc.perform(get("/api/v1/ping").with(token("COMPLIANCE_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pong"))
                .andExpect(jsonPath("$.subject").value("user-COMPLIANCE_OFFICER"))
                .andExpect(jsonPath("$.roles", contains("COMPLIANCE_OFFICER")));
    }

    @Test
    void corsPreflightFromAllowedOriginIsAccepted() throws Exception {
        mvc.perform(options("/api/v1/ping")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void corsPreflightFromOtherOriginIsRejected() throws Exception {
        mvc.perform(options("/api/v1/ping")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void corsActualRequestExposesCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/ping").with(token("CUSTOMER")).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(CorrelationIdFilter.HEADER)));
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/api/v1/ping").with(token("CUSTOMER")).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void errorResponsesCarrySecurityHeadersToo() throws Exception {
        mvc.perform(get("/api/v1/admin/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void isStatelessNoSessionNoCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/ping").with(token("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void webhookPostNeedsNoCsrfToken() throws Exception {
        mvc.perform(post("/api/v1/webhooks/probe").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    /** A mock JWT as Keycloak would issue it, converted by the production {@link JwtRoleConverter}. */
    private static RequestPostProcessor token(String role) {
        List<String> roles = "NO_ROLES".equals(role) ? List.of("default-roles-trustledger")
                : List.of(role, "default-roles-trustledger", "offline_access");
        return jwt()
                .jwt(j -> j.subject("user-" + role).claim("realm_access", Map.of("roles", roles)))
                .authorities(new JwtRoleConverter());
    }
}
