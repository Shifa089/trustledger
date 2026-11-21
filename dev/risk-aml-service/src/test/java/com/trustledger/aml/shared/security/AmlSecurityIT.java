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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The AML service's security chain (SPEC §5 "AML service", §6 "Security"). Tokens are built by MockMvc's
 * {@code jwt()} and mapped by the service's own {@link JwtRoleConverter}, so the
 * {@code realm_access.roles} mapping is the real one; signature, issuer and audience checks are the decoder's
 * ({@link AudienceValidatorTest}, and core-app's KeycloakRealmIT against a real Keycloak).
 * <p>
 * No AML endpoint exists yet, so an allowed request ends in 404 (it got past security) and a refused one in 401
 * or 403.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmlSecurityIT {

    private static final String ALERTS = "/api/v1/aml/alerts";

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void noTokenGets401AsProblemDetail() throws Exception {
        mvc.perform(get(ALERTS))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", Matchers.startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/unauthenticated"));
    }

    @Test
    void customersAuditorsAndAdminsCannotReachTheAmlApi() throws Exception {
        for (String role : List.of("CUSTOMER", "AUDITOR", "ADMIN")) {
            mvc.perform(get(ALERTS).with(token(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/access-denied"));
        }
        mvc.perform(post("/api/v1/aml/cases/1/close").with(token("CUSTOMER"))).andExpect(status().isForbidden());
    }

    @Test
    void aComplianceOfficerGetsPastSecurity() throws Exception {
        mvc.perform(get(ALERTS).with(token("COMPLIANCE_OFFICER"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/aml/cases").with(token("COMPLIANCE_OFFICER"))).andExpect(status().isNotFound());
    }

    @Test
    void anUnlistedPathIsRefusedEvenForAnOfficer() throws Exception {
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

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.API_CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    /** A Keycloak-shaped token with one realm role, mapped by the service's own converter. */
    private RequestPostProcessor token(String role) {
        return jwt().jwt(jwt -> jwt.subject("user-1").claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new JwtRoleConverter());
    }
}
