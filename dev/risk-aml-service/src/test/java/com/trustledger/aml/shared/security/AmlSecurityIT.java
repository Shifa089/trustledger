package com.trustledger.aml.shared.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.aml.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The AML service's security matrix (SPEC §5 "AML service", §6 "Security"): every endpoint, 401 without a token,
 * 403 for every role but COMPLIANCE_OFFICER, and past security for an officer. Tokens are built by MockMvc's
 * {@code jwt()} and mapped by the service's own {@link JwtRoleConverter}, so the {@code realm_access.roles} mapping
 * is the real one; signature, issuer and audience checks are the decoder's ({@link AudienceValidatorTest}, and
 * core-app's KeycloakRealmIT against a real Keycloak).
 * <p>
 * An officer's request to an unknown case ends in 404 (it got past security). A path without its own rule is
 * refused even for an officer.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmlSecurityIT {

    private static final String ALERTS = "/api/v1/aml/alerts";
    private static final String CASES = "/api/v1/aml/cases";
    private static final String UNKNOWN_CASE = CASES + "/" + UUID.randomUUID();
    private static final String NOTES = "{\"notes\":\"x\"}";
    private static final String CLOSE = "{\"resolution\":\"FALSE_POSITIVE\",\"notes\":\"x\"}";

    /** Every AML endpoint, as a fresh request each time; the officer's expected status beside it. */
    private record Endpoint(Supplier<MockHttpServletRequestBuilder> request, int officerStatus) {
    }

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(() -> get(ALERTS), 200),
            new Endpoint(() -> get(CASES), 200),
            new Endpoint(() -> get(UNKNOWN_CASE), 404),
            new Endpoint(() -> get(UNKNOWN_CASE + "/str-report"), 404),
            new Endpoint(() -> post(UNKNOWN_CASE + "/assign"), 404),
            new Endpoint(() -> json(post(UNKNOWN_CASE + "/close"), CLOSE), 404),
            new Endpoint(() -> json(post(UNKNOWN_CASE + "/escalate"), NOTES), 404));

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void noTokenGets401AsProblemDetail() throws Exception {
        for (Endpoint endpoint : ENDPOINTS) {
            mvc.perform(endpoint.request().get())
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", Matchers.startsWith("Bearer")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/unauthenticated"));
        }
    }

    @Test
    void customersAuditorsAndAdminsCannotReachTheAmlApi() throws Exception {
        for (String role : List.of("CUSTOMER", "AUDITOR", "ADMIN")) {
            for (Endpoint endpoint : ENDPOINTS) {
                mvc.perform(endpoint.request().get().with(token(role)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/access-denied"));
            }
        }
    }

    @Test
    void aComplianceOfficerGetsPastSecurityOnEveryEndpoint() throws Exception {
        for (Endpoint endpoint : ENDPOINTS) {
            mvc.perform(endpoint.request().get().with(token("COMPLIANCE_OFFICER")))
                    .andExpect(status().is(endpoint.officerStatus()));
        }
        // Past security, then refused by API versioning: only v1 exists.
        mvc.perform(get("/api/v2/aml/cases").with(token("COMPLIANCE_OFFICER"))).andExpect(status().isBadRequest());
    }

    @Test
    void anUnlistedPathIsRefusedEvenForAnOfficer() throws Exception {
        // POST /aml/cases/{id}/ai-summary is in SPEC §5 but deferred (M15): no rule, so denied.
        mvc.perform(post(UNKNOWN_CASE + "/ai-summary").with(token("COMPLIANCE_OFFICER")))
                .andExpect(status().isForbidden());
        // The alert list is read-only: POST has no rule.
        mvc.perform(post(ALERTS).with(token("COMPLIANCE_OFFICER"))).andExpect(status().isForbidden());
        // A known path with the wrong method has no rule either.
        mvc.perform(post(CASES).with(token("COMPLIANCE_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(get(UNKNOWN_CASE + "/close").with(token("COMPLIANCE_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/transfers").with(token("COMPLIANCE_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/anything").with(token("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublicTheRestOfActuatorIsAdminOnly() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator").with(token("COMPLIANCE_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator").with(token("ADMIN"))).andExpect(status().isOk());
    }

    // --- M12 actuator: probes public, health details and metrics ADMIN only -------------------------------------

    @Test
    void livenessAndReadinessProbesArePublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void healthComponentsAreShownOnlyToAnAdmin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health").with(token("COMPLIANCE_OFFICER")))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health").with(token("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        mvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness").with(token("ADMIN")))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void metricsAreAdminOnlyAndListTheAlertCounter() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(token("COMPLIANCE_OFFICER"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(token("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.names", Matchers.hasItem("trustledger.aml.alerts")));
        mvc.perform(get("/actuator/metrics/trustledger.aml.alerts").with(token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'rule')].values[*]",
                        Matchers.hasItems("R1", "R2", "R3", "R4")));
        mvc.perform(get("/actuator/env").with(token("ADMIN"))).andExpect(status().isNotFound());
    }

    // --- Swagger (ADR 0010): outside the local profile, any signed-in caller; never anonymous ----------------------

    @Test
    void swaggerNeedsATokenOutsideTheLocalProfile() throws Exception {
        for (String path : new String[] {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theOpenApiDocumentListsEveryAmlEndpointAndTheBearerScheme() throws Exception {
        mvc.perform(get("/v3/api-docs").with(token("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TrustLedger Risk and AML API"))
                .andExpect(jsonPath("$.paths", Matchers.hasKey("/api/v1/aml/alerts")))
                .andExpect(jsonPath("$.paths", Matchers.hasKey("/api/v1/aml/cases")))
                .andExpect(jsonPath("$.paths", Matchers.hasKey("/api/v1/aml/cases/{id}/str-report")))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A Keycloak-shaped token with one realm role (subject a UUID, as Keycloak's), mapped by our converter. */
    private RequestPostProcessor token(String role) {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new JwtRoleConverter());
    }
}
