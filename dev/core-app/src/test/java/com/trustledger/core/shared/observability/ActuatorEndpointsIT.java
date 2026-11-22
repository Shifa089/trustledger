package com.trustledger.core.shared.observability;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.shared.security.JwtRoleConverter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/**
 * M12 actuator (SPEC §6 Security): liveness and readiness probes are public, an anonymous health call gets only the
 * status, an ADMIN sees the components, and the custom metrics are listed under {@code /actuator/metrics} for ADMIN.
 * Who may call which path is in {@code SecurityMatrixIT}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ActuatorEndpointsIT {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    @Test
    void livenessAndReadinessProbesArePublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void anonymousHealthShowsOnlyTheStatusAndAdminSeesTheComponents() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health").with(token("CUSTOMER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health").with(token("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"));
    }

    @Test
    void readinessIncludesTheDatabaseForAnAdmin() throws Exception {
        mvc.perform(get("/actuator/health/readiness").with(token("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void theCustomMetricsAreListedForAnAdmin() throws Exception {
        mvc.perform(get("/actuator/metrics").with(token("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.names", hasItems("trustledger.transfers", "trustledger.transfer.amount",
                        "trustledger.topups", "trustledger.reconciliation.mismatches",
                        "trustledger.gateway.circuit.state")));
    }

    private static RequestPostProcessor token(String role) {
        return jwt().jwt(j -> j.subject("actuator-" + role).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new JwtRoleConverter());
    }
}
