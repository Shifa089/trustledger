package com.trustledger.core.kyc;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.ApiMockMvc.token;
import static com.trustledger.core.kyc.KycFlows.PDF;
import static com.trustledger.core.kyc.KycFlows.PNG;
import static com.trustledger.core.kyc.KycFlows.officer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.IdentityApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * The compliance side of full KYC (FR-KYC-3, FR-KYC-4): queue, maker-checker, rejection and presigned document
 * links, end to end against PostgreSQL and S3 (LocalStack).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ComplianceKycIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdentityApi identity;

    MockMvc mvc;
    KycFlows kyc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        kyc = new KycFlows(mvc);
    }

    // ---- maker-checker ----

    @Test
    void officerAVerifiesOfficerBApprovesAndTheCustomerIsFull() throws Exception {
        String customer = ApiMockMvc.newSubject();
        String officerA = ApiMockMvc.newSubject();
        String officerB = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(customer);

        kyc.decide(officerA, caseId, "verify")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.verifiedBy").value(userId(officerA).toString()));
        kyc.decide(officerB, caseId, "approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value(userId(officerB).toString()));

        mvc.perform(get("/api/v1/kyc/me").with(customer(customer)))
                .andExpect(jsonPath("$.currentLevel").value("FULL"));
        Map<String, Object> row = jdbc.queryForMap("select * from kyc.kyc_cases where id = ?", caseId);
        assertThat(row.get("verified_by")).isEqualTo(userId(officerA));
        assertThat(row.get("approved_by")).isEqualTo(userId(officerB));
        assertThat(row.get("approved_at")).isNotNull();
    }

    @Test
    void sameOfficerVerifyingAndApprovingIs409MakerCheckerViolation() throws Exception {
        String officerA = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());
        kyc.decide(officerA, caseId, "verify").andExpect(status().isOk());

        kyc.decide(officerA, caseId, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MAKER_CHECKER_VIOLATION"));

        Map<String, Object> row = jdbc.queryForMap("select status, approved_by from kyc.kyc_cases where id = ?", caseId);
        assertThat(row.get("status")).isEqualTo("VERIFIED");
        assertThat(row.get("approved_by")).isNull();
    }

    @Test
    void anOfficerCannotReviewTheirOwnCase() throws Exception {
        String dualRole = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(dualRole);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/compliance/kyc-cases/{id}/verify", caseId)
                        .with(token(dualRole, "both@example.test", "Dual Role", "CUSTOMER", "COMPLIANCE_OFFICER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MAKER_CHECKER_VIOLATION"));
    }

    @Test
    void approveBeforeVerifyIs409() throws Exception {
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());

        kyc.decide(ApiMockMvc.newSubject(), caseId, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void verifyNeedsBothRequiredDocuments() throws Exception {
        String customer = ApiMockMvc.newSubject();
        String officerA = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(customer);

        kyc.decide(officerA, caseId, "verify")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KYC_DOCUMENTS_MISSING"));
        kyc.uploadOk(customer, caseId, "PAN_CARD", PNG);
        kyc.decide(officerA, caseId, "verify")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ADDRESS_PROOF")));
        kyc.uploadOk(customer, caseId, "ADDRESS_PROOF", PDF);
        kyc.decide(officerA, caseId, "verify").andExpect(status().isOk());
    }

    @Test
    void anythingAfterApprovalIs409() throws Exception {
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());
        kyc.decide(ApiMockMvc.newSubject(), caseId, "verify").andExpect(status().isOk());
        kyc.decide(ApiMockMvc.newSubject(), caseId, "approve").andExpect(status().isOk());

        kyc.decide(ApiMockMvc.newSubject(), caseId, "approve").andExpect(status().isConflict());
        kyc.decide(ApiMockMvc.newSubject(), caseId, "verify").andExpect(status().isConflict());
        kyc.reject(ApiMockMvc.newSubject(), caseId, "Too late").andExpect(status().isConflict());
    }

    @Test
    void unknownCaseIs404() throws Exception {
        kyc.decide(ApiMockMvc.newSubject(), UUID.randomUUID(), "verify").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/compliance/kyc-cases/{id}", UUID.randomUUID()).with(officer(ApiMockMvc.newSubject())))
                .andExpect(status().isNotFound());
    }

    /** Two checkers press "approve" at the same moment: exactly one wins, the other gets a clean 409. */
    @Test
    void concurrentApprovalsByTwoOfficersApproveExactlyOnce() throws Exception {
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());
        kyc.decide(ApiMockMvc.newSubject(), caseId, "verify").andExpect(status().isOk());
        List<String> checkers = List.of(ApiMockMvc.newSubject(), ApiMockMvc.newSubject());
        for (String checker : checkers) { // provision both first, so only the approvals race
            mvc.perform(get("/api/v1/me").with(officer(checker))).andExpect(status().isOk());
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String checker : checkers) {
                results.add(pool.submit(() -> {
                    start.await();
                    return kyc.decide(checker, caseId, "approve").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) {
                statuses.add(r.get());
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select status from kyc.kyc_cases where id = ?", String.class, caseId))
                .isEqualTo("APPROVED");
    }

    // ---- reject ----

    @Test
    void rejectNeedsAReasonAndTheCustomerSeesIt() throws Exception {
        String customer = ApiMockMvc.newSubject();
        String officerA = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(customer);

        kyc.reject(officerA, caseId, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("reason"));
        kyc.reject(officerA, caseId, "   ").andExpect(status().isBadRequest());
        kyc.reject(officerA, caseId, "x".repeat(501)).andExpect(status().isBadRequest());

        kyc.reject(officerA, caseId, "Address proof is unreadable")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectedBy").value(userId(officerA).toString()));
        mvc.perform(get("/api/v1/kyc/me").with(customer(customer)))
                .andExpect(jsonPath("$.currentLevel").value("MIN"))
                .andExpect(jsonPath("$.cases[1].rejectionReason").value("Address proof is unreadable"));
    }

    @Test
    void theCheckerCanRejectAVerifiedCase() throws Exception {
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());
        kyc.decide(ApiMockMvc.newSubject(), caseId, "verify").andExpect(status().isOk());

        kyc.reject(ApiMockMvc.newSubject(), caseId, "Photo does not match").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ---- queue and detail ----

    @Test
    void queueListsFullCasesOfOneStatusOldestFirstWithoutPersonalData() throws Exception {
        UUID submitted = kyc.readyCase(ApiMockMvc.newSubject());
        UUID verified = kyc.readyCase(ApiMockMvc.newSubject());
        kyc.decide(ApiMockMvc.newSubject(), verified, "verify").andExpect(status().isOk());
        String officerSubject = ApiMockMvc.newSubject();

        List<Map<String, Object>> submittedItems = allItems(officerSubject, "SUBMITTED");
        assertThat(submittedItems).extracting(i -> i.get("id")).contains(submitted.toString())
                .doesNotContain(verified.toString());
        assertThat(submittedItems).allSatisfy(i -> assertThat(i.get("status")).isEqualTo("SUBMITTED"));
        List<String> created = submittedItems.stream().map(i -> (String) i.get("createdAt")).toList();
        assertThat(created).isSortedAccordingTo((a, b) -> Instant.parse(a).compareTo(Instant.parse(b)));

        Map<String, Object> ours = submittedItems.stream().filter(i -> i.get("id").equals(submitted.toString()))
                .findFirst().orElseThrow();
        assertThat((List<?>) ours.get("documents")).hasSize(2);
        assertThat(ours).doesNotContainKey("address");

        assertThat(allItems(officerSubject, "VERIFIED")).extracting(i -> i.get("id")).contains(verified.toString());
    }

    @Test
    void queueValidatesItsParameters() throws Exception {
        String officerSubject = ApiMockMvc.newSubject();
        mvc.perform(get("/api/v1/compliance/kyc-cases").param("size", "101").with(officer(officerSubject)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/compliance/kyc-cases").param("page", "-1").with(officer(officerSubject)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/compliance/kyc-cases").param("status", "PENDING").with(officer(officerSubject)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/compliance/kyc-cases").param("size", "1").with(officer(officerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void detailShowsTheAddressAndTheDocumentsToAnOfficer() throws Exception {
        UUID caseId = kyc.readyCase(ApiMockMvc.newSubject());

        mvc.perform(get("/api/v1/compliance/kyc-cases/{id}", caseId).with(officer(ApiMockMvc.newSubject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(KycFlows.ADDRESS))
                .andExpect(jsonPath("$.documents", hasSize(2)))
                .andExpect(jsonPath("$.documents[0].type").value("PAN_CARD"))
                .andExpect(jsonPath("$.documents[0].sha256").value(KycFlows.sha256(PNG)));
    }

    // ---- document view ----

    @Test
    void documentViewIsAFiveMinutePresignedDownloadOfTheExactBytes() throws Exception {
        String customer = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(customer);
        UUID docId = kyc.uploadOk(customer, caseId, "PAN_CARD", PNG);
        Instant before = Instant.now();

        String body = mvc.perform(get("/api/v1/compliance/kyc-cases/{id}/documents/{docId}", caseId, docId)
                        .with(officer(ApiMockMvc.newSubject())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        URI url = URI.create(JsonPath.read(body, "$.url"));
        Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));

        assertThat(url.getQuery()).contains("X-Amz-Expires=300").contains("X-Amz-Signature=");
        assertThat(Duration.between(before, expiresAt)).isBetween(Duration.ofSeconds(290), Duration.ofSeconds(310));

        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(PNG);
            assertThat(download.headers().firstValue("Content-Type")).hasValue("image/png");
            assertThat(download.headers().firstValue("Content-Disposition")).hasValue("attachment");

            // The signature covers the key: pointing the same link at another object is refused.
            URI tampered = URI.create(url.toString().replace(caseId.toString(), UUID.randomUUID().toString()));
            assertThat(http.send(HttpRequest.newBuilder(tampered).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void documentOfAnotherCaseIs404() throws Exception {
        String alice = ApiMockMvc.newSubject();
        UUID aliceCase = kyc.fullCase(alice);
        UUID aliceDoc = kyc.uploadOk(alice, aliceCase, "PAN_CARD", PNG);
        UUID bobCase = kyc.fullCase(ApiMockMvc.newSubject());

        mvc.perform(get("/api/v1/compliance/kyc-cases/{id}/documents/{docId}", bobCase, aliceDoc)
                        .with(officer(ApiMockMvc.newSubject())))
                .andExpect(status().isNotFound());
    }

    @Test
    void customersCannotUseComplianceEndpoints() throws Exception {
        String customer = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(customer);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/compliance/kyc-cases/{id}/verify", caseId).with(customer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/compliance/kyc-cases/{id}", caseId).with(customer(customer)))
                .andExpect(status().isForbidden());
    }

    private List<Map<String, Object>> allItems(String officerSubject, String status) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            String body = mvc.perform(get("/api/v1/compliance/kyc-cases").param("status", status)
                            .param("page", String.valueOf(page)).param("size", "100").with(officer(officerSubject)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> items = JsonPath.read(body, "$.items");
            all.addAll(items);
            int totalPages = JsonPath.read(body, "$.totalPages");
            if (page + 1 >= totalPages) {
                return all;
            }
        }
    }

    private UUID userId(String subject) {
        return identity.findUserId(subject).orElseThrow();
    }
}
