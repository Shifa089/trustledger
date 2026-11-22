package com.trustledger.core.kyc;

import static com.trustledger.core.kyc.KycFlows.officer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.audit.AuditRows;
import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-A-1 (Day 20): every KYC compliance action (verify, approve, reject, document view) writes one audit event with
 * the officer, role, IP, user agent and correlation ID, and a masked before/after: no name, PAN, date of birth,
 * address, reason text or presigned URL ever reaches the trail. A refused attempt is recorded as REJECTED.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KycAuditedActionsIT {

    private static final String USER_AGENT = "TrustLedger-Console/2.0 (compliance)";

    @Autowired
    WebApplicationContext context;

    @Autowired
    IdentityApi identity;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    MockMvc mvc;
    KycFlows kyc;
    AuditRows rows;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        kyc = new KycFlows(mvc);
        rows = new AuditRows(jdbc, json);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String officerSubject, String correlationId,
            String ip) throws Exception {
        return mvc.perform(request.with(officer(officerSubject))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header(CorrelationIdFilter.HEADER, correlationId));
    }

    private UUID userId(String subject) {
        return identity.findUserId(subject).orElseThrow();
    }

    /** The jsonb text of every event about this entity: what a leak would show up in. */
    private String trailText(UUID entityId) {
        List<String> texts = jdbc.queryForList("select coalesce(before::text, '') || coalesce(after::text, '') "
                + "from audit.audit_events where entity_id = ?", String.class, entityId);
        return String.join("\n", texts);
    }

    @Test
    void verifyAndApproveAreAuditedWithWhoWhereAndTheStateChange() throws Exception {
        String officerA = ApiMockMvc.newSubject();
        String officerB = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());

        call(post("/api/v1/compliance/kyc-cases/{id}/verify", caseId), officerA, "kyc-verify-1", "203.0.113.10")
                .andExpect(status().isOk());
        call(post("/api/v1/compliance/kyc-cases/{id}/approve", caseId), officerB, "kyc-approve-1", "2001:db8::10")
                .andExpect(status().isOk());

        AuditEvent verify = rows.single(caseId, "KYC_CASE_VERIFY").event();
        assertThat(verify.actorId()).isEqualTo(userId(officerA));
        assertThat(verify.actorRole()).isEqualTo("COMPLIANCE_OFFICER");
        assertThat(verify.entityType()).isEqualTo("KYC_CASE");
        assertThat(verify.ip()).isEqualTo("203.0.113.10");
        assertThat(verify.userAgent()).isEqualTo(USER_AGENT);
        assertThat(verify.correlationId()).isEqualTo("kyc-verify-1");
        assertThat(verify.before()).isEqualTo(Map.of("level", "FULL", "status", "SUBMITTED"));
        assertThat(verify.after()).isEqualTo(Map.of("level", "FULL", "status", "VERIFIED",
                "verifiedBy", userId(officerA).toString(), "outcome", "SUCCEEDED"));

        AuditEvent approve = rows.single(caseId, "KYC_CASE_APPROVE").event();
        assertThat(approve.actorId()).isEqualTo(userId(officerB));
        assertThat(approve.ip()).isEqualTo("2001:db8::10");
        assertThat(approve.before()).containsEntry("status", "VERIFIED");
        assertThat(approve.after()).containsEntry("status", "APPROVED")
                .containsEntry("approvedBy", userId(officerB).toString())
                .containsEntry("outcome", "SUCCEEDED");

        // The domain fact of the same request, written in the business transaction (Day 19), shares the
        // correlation ID, so an auditor can join the two.
        // Minimum KYC wrote one too (level MIN), so pick the FULL upgrade.
        assertThat(rows.forEntity(customerOf(caseId)).stream().map(AuditRows.Row::event)
                .filter(e -> e.action().equals("KYC_LEVEL_UPGRADED"))
                .filter(e -> "FULL".equals(e.after().get("kycLevel"))))
                .singleElement()
                .satisfies(e -> assertThat(e.correlationId()).isEqualTo("kyc-approve-1"));
        rows.assertChainIntact();
    }

    private UUID customerOf(UUID caseId) {
        return jdbc.queryForObject("select user_id from kyc.kyc_cases where id = ?", UUID.class, caseId);
    }

    @Test
    void theMakerTryingToApproveIsRecordedAsRejected() throws Exception {
        String officerA = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());
        call(post("/api/v1/compliance/kyc-cases/{id}/verify", caseId), officerA, "kyc-mc-1", "203.0.113.11")
                .andExpect(status().isOk());

        call(post("/api/v1/compliance/kyc-cases/{id}/approve", caseId), officerA, "kyc-mc-2", "203.0.113.11")
                .andExpect(status().isConflict());

        AuditEvent refused = rows.single(caseId, "KYC_CASE_APPROVE").event();
        assertThat(refused.actorId()).isEqualTo(userId(officerA));
        assertThat(refused.correlationId()).isEqualTo("kyc-mc-2");
        assertThat(refused.before()).containsEntry("status", "VERIFIED");
        assertThat(refused.after()).isEqualTo(Map.of("outcome", "REJECTED",
                "errorCode", "MAKER_CHECKER_VIOLATION"));
        assertThat(jdbc.queryForObject("select status from kyc.kyc_cases where id = ?", String.class, caseId))
                .isEqualTo("VERIFIED");
    }

    @Test
    void aRejectionKeepsOnlyTheLengthOfTheReasonAndNoPersonalData() throws Exception {
        String officerA = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());
        // A different fake PAN from the customer's: the reason is stored in clear in kyc_cases, which MinKycIT scans.
        String reason = "PAN ZZZPZ9876Z does not match the name Kyc Full Person";

        call(post("/api/v1/compliance/kyc-cases/{id}/reject", caseId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}"), officerA, "kyc-reject-1", "203.0.113.12")
                .andExpect(status().isOk());

        AuditEvent rejected = rows.single(caseId, "KYC_CASE_REJECT").event();
        assertThat(rejected.after()).containsEntry("status", "REJECTED")
                .containsEntry("rejectedBy", userId(officerA).toString())
                .containsEntry("reasonLength", reason.length())
                .containsEntry("outcome", "SUCCEEDED");
        assertThat(trailText(caseId)).doesNotContain("ZZZPZ9876Z", "ABCPE1234F", "Kyc Full Person", "1990-06-01",
                KycFlows.ADDRESS, "Indiranagar", "does not match");
    }

    @Test
    void openingADocumentIsAuditedWithoutTheLink() throws Exception {
        String officerA = ApiMockMvc.newSubject();
        String customer = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(customer);
        UUID documentId = kyc.uploadOk(customer, caseId, "PAN_CARD", KycFlows.PNG);

        String body = call(get("/api/v1/compliance/kyc-cases/{id}/documents/{docId}", caseId, documentId), officerA,
                "kyc-doc-1", "203.0.113.13").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String url = JsonPath.read(body, "$.url");

        AuditEvent view = rows.single(documentId, "KYC_DOCUMENT_VIEW").event();
        assertThat(view.entityType()).isEqualTo("KYC_DOCUMENT");
        assertThat(view.actorId()).isEqualTo(userId(officerA));
        assertThat(view.before()).isNull();
        assertThat(view.after()).isEqualTo(Map.of("caseId", caseId.toString(), "documentType", "PAN_CARD",
                "outcome", "SUCCEEDED"));
        assertThat(trailText(documentId)).doesNotContain(url, "X-Amz-Signature", "http");
    }

    @Test
    void openingAnotherCasesDocumentIsRecordedAsRejected() throws Exception {
        String customer = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(customer);
        UUID documentId = kyc.uploadOk(customer, caseId, "PAN_CARD", KycFlows.PNG);
        UUID otherCase = kyc.fullCase(ApiMockMvc.newSubject());

        call(get("/api/v1/compliance/kyc-cases/{id}/documents/{docId}", otherCase, documentId),
                ApiMockMvc.newSubject(), "kyc-doc-2", "203.0.113.14").andExpect(status().isNotFound());

        assertThat(rows.single(documentId, "KYC_DOCUMENT_VIEW").event().after())
                .isEqualTo(Map.of("outcome", "REJECTED", "errorCode", "NOT_FOUND"));
    }
}
