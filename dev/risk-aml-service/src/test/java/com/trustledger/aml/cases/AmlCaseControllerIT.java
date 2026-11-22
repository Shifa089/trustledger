package com.trustledger.aml.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.aml.facts.application.TransactionFactRecorder;
import com.trustledger.aml.shared.security.JwtRoleConverter;
import com.trustledger.events.TransferCompletedV1;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * FR-AML-4 through HTTP, against real PostgreSQL: list, detail, assign, close, escalate, the 409s for illegal steps,
 * validation, and the STR CSV export with masked identifiers. Each test builds its own case from three structured
 * transfers (R2, HIGH) of a fresh wallet.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmlCaseControllerIT {

    private static final String CASES = "/api/v1/aml/cases";
    private static final long STRUCTURED = 4_600_000;
    private static final UUID OFFICER = UUID.randomUUID();
    private static final UUID OTHER_OFFICER = UUID.randomUUID();

    @Autowired
    WebApplicationContext context;

    @Autowired
    TransactionFactRecorder recorder;

    @Autowired
    JdbcClient jdbc;

    private MockMvc mvc;
    private UUID wallet;
    private UUID receiver;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        wallet = UUID.randomUUID();
        receiver = UUID.randomUUID();
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            recorder.record(new TransferCompletedV1(UUID.randomUUID(), now.minusSeconds(60L * (i + 1)), wallet,
                    receiver, UUID.randomUUID(), STRUCTURED, "INR"));
        }
        caseId = jdbc.sql("SELECT id FROM aml.cases WHERE wallet_id = :w").param("w", wallet)
                .query(UUID.class).single();
    }

    // --- reading ---------------------------------------------------------------------------------------------------

    @Test
    void theListShowsTheCaseWithItsAlertCountAndHighestSeverity() throws Exception {
        mvc.perform(get(CASES).param("walletId", wallet.toString()).with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(caseId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].alertCount").value(1))
                .andExpect(jsonPath("$.content[0].highestSeverity").value("HIGH"));
        mvc.perform(get(CASES).param("walletId", wallet.toString()).param("status", "ESCALATED")
                        .with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get(CASES).param("status", "OPEN").param("size", "100").with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void theDetailShowsAlertsTheFreezeRequestAndTheWalletsFacts() throws Exception {
        mvc.perform(get(CASES + "/" + caseId).with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(wallet.toString()))
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].ruleCode").value("R2"))
                .andExpect(jsonPath("$.freezeRequests.length()").value(1))
                .andExpect(jsonPath("$.freezeRequests[0].ruleCode").value("R2"))
                .andExpect(jsonPath("$.facts.length()").value(3))
                .andExpect(jsonPath("$.facts[0].direction").value("OUT"))
                .andExpect(jsonPath("$.facts[0].counterpartyWalletId").value(receiver.toString()))
                .andExpect(jsonPath("$.facts[0].amount").value("46000.00"));
    }

    @Test
    void badQueryValuesAndUnknownCasesAreRefused() throws Exception {
        mvc.perform(get(CASES).param("size", "101").with(officer(OFFICER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(get(CASES).param("status", "NOT_A_STATUS").with(officer(OFFICER)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(CASES + "/not-a-uuid").with(officer(OFFICER))).andExpect(status().isBadRequest());
        mvc.perform(get(CASES + "/" + UUID.randomUUID()).with(officer(OFFICER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        mvc.perform(json(post(CASES + "/" + UUID.randomUUID() + "/assign"), "{}").with(officer(OFFICER)))
                .andExpect(status().isNotFound());
    }

    // --- workflow --------------------------------------------------------------------------------------------------

    @Test
    void assignWithoutABodyAssignsTheCaller() throws Exception {
        mvc.perform(post(CASES + "/" + caseId + "/assign").with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.assignedTo").value(OFFICER.toString()));
    }

    @Test
    void aCaseCanBeAssignedToAnotherOfficerAndReassigned() throws Exception {
        mvc.perform(json(post(CASES + "/" + caseId + "/assign"), "{\"assigneeId\":\"" + OTHER_OFFICER + "\"}")
                        .with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value(OTHER_OFFICER.toString()));
        mvc.perform(json(post(CASES + "/" + caseId + "/assign"), "{}").with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.assignedTo").value(OFFICER.toString()));
    }

    @Test
    void theAssigneeClosesAFalsePositive() throws Exception {
        assign(OFFICER);

        mvc.perform(json(post(CASES + "/" + caseId + "/close"),
                        "{\"resolution\":\"FALSE_POSITIVE\",\"notes\":\"Rent payments to a known landlord.\"}")
                        .with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED_FALSE_POSITIVE"))
                .andExpect(jsonPath("$.resolution").value("Rent payments to a known landlord."));
        // Finished: the next alert for the wallet opens a new case; this one can't be picked up again.
        mvc.perform(post(CASES + "/" + caseId + "/assign").with(officer(OFFICER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void illegalTransitionsAre409() throws Exception {
        // OPEN: must be investigated first.
        mvc.perform(json(post(CASES + "/" + caseId + "/escalate"), "{\"notes\":\"x\"}").with(officer(OFFICER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        mvc.perform(json(post(CASES + "/" + caseId + "/close"), "{\"resolution\":\"FALSE_POSITIVE\",\"notes\":\"x\"}")
                        .with(officer(OFFICER)))
                .andExpect(status().isConflict());
        // Not the assignee.
        assign(OFFICER);
        mvc.perform(json(post(CASES + "/" + caseId + "/escalate"), "{\"notes\":\"x\"}")
                        .with(officer(OTHER_OFFICER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOT_CASE_ASSIGNEE"));
        // Finished twice.
        escalate(OFFICER);
        mvc.perform(json(post(CASES + "/" + caseId + "/close"), "{\"resolution\":\"FALSE_POSITIVE\",\"notes\":\"x\"}")
                        .with(officer(OFFICER)))
                .andExpect(status().isConflict());
        assertThat(caseStatus(caseId)).isEqualTo("ESCALATED");
    }

    @Test
    void closeAndEscalateValidateTheirBodies() throws Exception {
        assign(OFFICER);
        String path = CASES + "/" + caseId;

        mvc.perform(json(post(path + "/close"), "{\"resolution\":\"FALSE_POSITIVE\"}").with(officer(OFFICER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("notes"));
        mvc.perform(json(post(path + "/close"), "{\"notes\":\"x\"}").with(officer(OFFICER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("resolution"));
        mvc.perform(json(post(path + "/close"), "{\"resolution\":\"ESCALATED\",\"notes\":\"x\"}")
                        .with(officer(OFFICER)))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post(path + "/escalate"), "{\"notes\":\"" + "x".repeat(2001) + "\"}").with(officer(OFFICER)))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post(path + "/escalate"), "{\"notes\":\"   \"}").with(officer(OFFICER)))
                .andExpect(status().isBadRequest())
                // The rejected value is never echoed.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("rejectedValue"))));
        assertThat(caseStatus(caseId)).isEqualTo("INVESTIGATING");
    }

    // --- STR report ------------------------------------------------------------------------------------------------

    @Test
    void anEscalatedCaseExportsAMaskedCsv() throws Exception {
        assign(OFFICER);
        escalate(OFFICER);

        String csv = mvc.perform(get(CASES + "/" + caseId + "/str-report").with(officer(OFFICER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"str-" + caseId + ".csv\""))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString();

        List<String> lines = csv.lines().toList();
        assertThat(lines).hasSize(1 + 1 + 1 + 3); // header, case, one alert, three facts
        assertThat(lines.get(0)).startsWith("record_type,case_id,case_status,wallet");
        assertThat(lines.get(1)).startsWith("CASE," + caseId + ",ESCALATED,****" + last4(wallet));
        assertThat(lines.get(2)).contains("ALERT", ",R2,HIGH,");
        assertThat(lines.subList(3, 6)).allSatisfy(line -> assertThat(line)
                .startsWith("FACT,").contains(",OUT,TRANSFER,****" + last4(receiver)).contains(",46000.00,INR,"));
        assertThat(csv).doesNotContain(wallet.toString(), receiver.toString(), OFFICER.toString());
    }

    @Test
    void aCaseThatIsNotEscalatedHasNoStrReport() throws Exception {
        mvc.perform(get(CASES + "/" + caseId + "/str-report").with(officer(OFFICER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        mvc.perform(get(CASES + "/" + UUID.randomUUID() + "/str-report").with(officer(OFFICER)))
                .andExpect(status().isNotFound());
    }

    private void assign(UUID officer) throws Exception {
        mvc.perform(post(CASES + "/" + caseId + "/assign").with(officer(officer))).andExpect(status().isOk());
    }

    private void escalate(UUID officer) throws Exception {
        mvc.perform(json(post(CASES + "/" + caseId + "/escalate"), "{\"notes\":\"Structuring confirmed.\"}")
                        .with(officer(officer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"));
    }

    private String caseStatus(UUID id) {
        return jdbc.sql("SELECT status FROM aml.cases WHERE id = :id").param("id", id).query(String.class).single();
    }

    private static String last4(UUID id) {
        String text = id.toString();
        return text.substring(text.length() - 4);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestPostProcessor officer(UUID id) {
        return jwt().jwt(jwt -> jwt.subject(id.toString())
                        .claim("realm_access", Map.of("roles", List.of("COMPLIANCE_OFFICER"))))
                .authorities(new JwtRoleConverter());
    }
}
