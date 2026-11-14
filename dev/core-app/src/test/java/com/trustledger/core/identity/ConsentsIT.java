package com.trustledger.core.identity;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.ApiMockMvc.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** FR-ID-3 consents end to end: HTTP, validation, append-only history in PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConsentsIT {

    private static final String V = "2026-11-01"; // application.yml

    @Autowired
    WebApplicationContext context;

    @Autowired
    IdentityApi identityApi;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    String subject;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        subject = ApiMockMvc.newSubject();
    }

    @Test
    void newUserHasNoConsentsAndSeesTheCurrentVersions() throws Exception {
        mvc.perform(get("/api/v1/me/consents").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredGiven").value(false))
                .andExpect(jsonPath("$.consents", hasSize(0)))
                .andExpect(jsonPath("$.policyVersions.TERMS").value(V))
                .andExpect(jsonPath("$.policyVersions.MARKETING").value(V));
    }

    @Test
    void grantingTermsAndPrivacyIsStoredWithVersionTimeAndIp() throws Exception {
        consent("""
                {"consents":[{"purpose":"TERMS","policyVersion":"%s","granted":true},
                             {"purpose":"PRIVACY","policyVersion":"%s","granted":true}]}""".formatted(V, V))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiredGiven").value(true))
                .andExpect(jsonPath("$.consents", hasSize(2)))
                .andExpect(jsonPath("$.consents[0].purpose").value("TERMS"))
                .andExpect(jsonPath("$.consents[0].granted").value(true))
                .andExpect(jsonPath("$.consents[0].policyVersion").value(V))
                .andExpect(jsonPath("$.consents[0].capturedAt").exists());

        List<Map<String, Object>> rows = history();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("ip")).isEqualTo("127.0.0.1");
            assertThat(row.get("policy_version")).isEqualTo(V);
            assertThat(row.get("captured_at")).isNotNull();
        });

        mvc.perform(get("/api/v1/me/consents").with(customer(subject)))
                .andExpect(jsonPath("$.requiredGiven").value(true));
    }

    @Test
    void responseTimestampEqualsTheStoredOne() throws Exception {
        String posted = consent(all(false)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String read = mvc.perform(get("/api/v1/me/consents").with(customer(subject)))
                .andReturn().getResponse().getContentAsString();

        assertThat(read).isEqualTo(posted); // DB keeps microseconds; nanoseconds must not leak into the POST
    }

    @Test
    void marketingCanBeWithdrawnAndHistoryKeepsBothDecisions() throws Exception {
        consent(all(true)).andExpect(status().isCreated());

        consent("""
                {"consents":[{"purpose":"MARKETING","policyVersion":"%s","granted":false}]}""".formatted(V))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiredGiven").value(true))
                .andExpect(jsonPath("$.consents[?(@.purpose=='MARKETING')].granted").value(false));

        mvc.perform(get("/api/v1/me/consents").with(customer(subject)))
                .andExpect(jsonPath("$.consents", hasSize(3)))
                .andExpect(jsonPath("$.consents[?(@.purpose=='MARKETING')].granted").value(false));

        List<Map<String, Object>> marketing = history().stream()
                .filter(row -> "MARKETING".equals(row.get("purpose"))).toList();
        assertThat(marketing).extracting(row -> row.get("granted")).containsExactly(true, false);
    }

    @Test
    void termsCannotBeWithdrawn() throws Exception {
        consent(all(true)).andExpect(status().isCreated());

        consent("""
                {"consents":[{"purpose":"TERMS","policyVersion":"%s","granted":false}]}""".formatted(V))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail", containsString("cannot be withdrawn")));

        assertThat(history()).hasSize(3); // nothing appended
    }

    @Test
    void marketingAloneBeforeTermsAndPrivacyIsRejected() throws Exception {
        consent("""
                {"consents":[{"purpose":"MARKETING","policyVersion":"%s","granted":true}]}""".formatted(V))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("required")));

        assertThat(history()).isEmpty();
    }

    @Test
    void outdatedPolicyVersionIsRejected() throws Exception {
        consent("""
                {"consents":[{"purpose":"TERMS","policyVersion":"2020-01-01","granted":true},
                             {"purpose":"PRIVACY","policyVersion":"%s","granted":true}]}""".formatted(V))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not the current one")));

        assertThat(history()).isEmpty(); // all or nothing
    }

    @Test
    void malformedBodiesAreValidationErrors() throws Exception {
        consent("{\"consents\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        consent("{\"consents\":[{\"purpose\":\"TERMS\",\"policyVersion\":\"" + V + "\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("consents[0].granted"));
        consent("{\"consents\":[{\"purpose\":\"SELL_MY_DATA\",\"policyVersion\":\"" + V + "\",\"granted\":true}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        assertThat(history()).isEmpty();
    }

    @Test
    void consentsAreCustomerOnly() throws Exception {
        String officer = ApiMockMvc.newSubject();
        mvc.perform(get("/api/v1/me/consents")
                        .with(token(officer, "o@example.test", "Officer", "COMPLIANCE_OFFICER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(post("/api/v1/me/consents").contentType(MediaType.APPLICATION_JSON).content(all(true))
                        .with(token(officer, "o@example.test", "Officer", "ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/consents")).andExpect(status().isUnauthorized());
    }

    @Test
    void eachUserSeesOnlyTheirOwnConsents() throws Exception {
        consent(all(true)).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/me/consents").with(customer(ApiMockMvc.newSubject())))
                .andExpect(jsonPath("$.consents", hasSize(0)));
    }

    private ResultActions consent(String json) throws Exception {
        return mvc.perform(post("/api/v1/me/consents").with(customer(subject))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String all(boolean marketing) {
        return """
                {"consents":[{"purpose":"TERMS","policyVersion":"%1$s","granted":true},
                             {"purpose":"PRIVACY","policyVersion":"%1$s","granted":true},
                             {"purpose":"MARKETING","policyVersion":"%1$s","granted":%2$s}]}""".formatted(V, marketing);
    }

    private List<Map<String, Object>> history() {
        UUID userId = identityApi.findUserId(subject).orElseThrow();
        return jdbc.queryForList("select * from identity.consents where user_id = ? order by seq", userId);
    }
}
