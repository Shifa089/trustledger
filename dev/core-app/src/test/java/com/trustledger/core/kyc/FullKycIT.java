package com.trustledger.core.kyc;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.kyc.KycFlows.ADDRESS;
import static com.trustledger.core.kyc.KycFlows.EXE;
import static com.trustledger.core.kyc.KycFlows.JPEG;
import static com.trustledger.core.kyc.KycFlows.PDF;
import static com.trustledger.core.kyc.KycFlows.PNG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

/**
 * {@code POST /kyc/full} and {@code POST /kyc/cases/{id}/documents} end to end (FR-KYC-2), against real
 * PostgreSQL and S3 (LocalStack).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FullKycIT {

    private static final int FIVE_MB = 5 * 1024 * 1024;

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    S3Client s3;

    MockMvc mvc;
    KycFlows kyc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        kyc = new KycFlows(mvc);
    }

    // ---- POST /kyc/full ----

    @Test
    void fullKycOpensASubmittedCaseWithTheAddressEncrypted() throws Exception {
        String subject = ApiMockMvc.newSubject();
        kyc.minKyc(subject);

        String body = kyc.submitFull(subject, ADDRESS)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.level").value("FULL"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.requiredDocuments", contains("PAN_CARD", "ADDRESS_PROOF")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Test Street");

        UUID caseId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        String stored = jdbc.queryForObject("select address_enc from kyc.kyc_cases where id = ?", String.class, caseId);
        assertThat(stored).isNotBlank().doesNotContain("Test Street", "Bengaluru");

        mvc.perform(get("/api/v1/kyc/me").with(customer(subject)))
                .andExpect(jsonPath("$.currentLevel").value("MIN"))
                .andExpect(jsonPath("$.cases", hasSize(2)))
                .andExpect(jsonPath("$.cases[1].status").value("SUBMITTED"));
    }

    @Test
    void withoutMinimumKycIs403KycRequired() throws Exception {
        String subject = ApiMockMvc.newSubject();

        kyc.submitFull(subject, ADDRESS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("KYC_REQUIRED"));
    }

    @Test
    void secondFullCaseIs409() throws Exception {
        String subject = ApiMockMvc.newSubject();
        kyc.fullCase(subject);

        kyc.submitFull(subject, ADDRESS)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KYC_ALREADY_SUBMITTED"));
    }

    @Test
    void afterARejectionANewFullCaseIsAllowed() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID first = kyc.fullCase(subject);
        kyc.reject(ApiMockMvc.newSubject(), first, "Address proof unreadable").andExpect(status().isOk());

        kyc.submitFull(subject, ADDRESS).andExpect(status().isCreated());
    }

    @Test
    void blankOrOverlongAddressIs400() throws Exception {
        String subject = ApiMockMvc.newSubject();
        kyc.minKyc(subject);

        kyc.submitFull(subject, " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("address"));
        kyc.submitFull(subject, "x".repeat(501)).andExpect(status().isBadRequest());
    }

    // ---- POST /kyc/cases/{id}/documents ----

    @ParameterizedTest(name = "{0} as {1}")
    @CsvSource({"PAN_CARD, PNG, image/png", "ADDRESS_PROOF, PDF, application/pdf", "PHOTO, JPEG, image/jpeg"})
    void uploadStoresTheFileInS3AndItsShaInTheDatabase(String type, String format, String expectedContentType)
            throws Exception {
        byte[] content = switch (format) {
            case "PNG" -> PNG;
            case "PDF" -> PDF;
            default -> JPEG;
        };
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(subject);

        String sha = KycFlows.sha256(content);
        String body = kyc.upload(subject, caseId, type, content, "text/plain") // client's type is ignored
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.contentType").value(expectedContentType))
                .andExpect(jsonPath("$.sizeBytes").value(content.length))
                .andExpect(jsonPath("$.sha256").value(sha))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("s3Key", "kyc/", "upload.bin");

        Map<String, Object> row = jdbc.queryForMap("select * from kyc.kyc_documents where case_id = ?", caseId);
        assertThat(row.get("sha256")).isEqualTo(sha);
        assertThat(row.get("content_type")).isEqualTo(expectedContentType);
        String key = (String) row.get("s3_key");
        assertThat(key).startsWith("kyc/" + caseId + "/");

        ResponseBytes<GetObjectResponse> stored = s3.getObjectAsBytes(
                b -> b.bucket(TestcontainersConfiguration.KYC_BUCKET).key(key));
        assertThat(stored.asByteArray()).isEqualTo(content);
        assertThat(KycFlows.sha256(stored.asByteArray()))
                .isEqualTo(sha);
    }

    @Test
    void exactlyFiveMegabytesIsAccepted() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(subject);

        kyc.upload(subject, caseId, "ADDRESS_PROOF", KycFlows.bytes(PDF, FIVE_MB), "application/pdf")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sizeBytes").value(FIVE_MB));
    }

    @Test
    void oneByteOverFiveMegabytesIs413AndNothingIsStored() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(subject);

        kyc.upload(subject, caseId, "ADDRESS_PROOF", KycFlows.bytes(PDF, FIVE_MB + 1), "application/pdf")
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.errorCode").value("DOCUMENT_TOO_LARGE"));
        assertNothingStored(caseId);
    }

    @Test
    void executableDisguisedAsPdfIs415AndNothingIsStored() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(subject);

        kyc.upload(subject, caseId, "PAN_CARD", EXE, "application/pdf")
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_DOCUMENT_TYPE"));
        assertNothingStored(caseId);
    }

    @Test
    void emptyFileIs400() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(subject);

        kyc.upload(subject, caseId, "PAN_CARD", new byte[0], "application/pdf")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertNothingStored(caseId);
    }

    @Test
    void unknownDocumentTypeOrMissingFileIs400() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(subject);

        kyc.upload(subject, caseId, "PASSPORT_SCAN", PDF, "application/pdf").andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/kyc/cases/{id}/documents", caseId).param("type", "PAN_CARD")
                .with(customer(subject))).andExpect(status().isBadRequest());
        assertNothingStored(caseId);
    }

    @Test
    void anotherUsersCaseIs404NotForbiddenAndNothingIsStored() throws Exception {
        UUID aliceCase = kyc.fullCase(ApiMockMvc.newSubject());
        String mallory = ApiMockMvc.newSubject();
        kyc.minKyc(mallory);

        kyc.upload(mallory, aliceCase, "PAN_CARD", PNG, "image/png")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        kyc.upload(mallory, UUID.randomUUID(), "PAN_CARD", PNG, "image/png").andExpect(status().isNotFound());
        assertNothingStored(aliceCase);
    }

    @Test
    void verifiedCaseTakesNoMoreDocuments() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.readyCase(subject);
        kyc.decide(ApiMockMvc.newSubject(), caseId, "verify").andExpect(status().isOk());

        kyc.upload(subject, caseId, "PHOTO", JPEG, "image/jpeg")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        assertThat(documentCount(caseId)).isEqualTo(2);
    }

    @Test
    void theDocumentLimitPerCaseIsEnforced() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID caseId = kyc.fullCase(subject);
        for (int i = 0; i < 10; i++) {
            kyc.uploadOk(subject, caseId, "PHOTO", JPEG);
        }

        kyc.upload(subject, caseId, "PHOTO", JPEG, "image/jpeg")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("LIMIT_EXCEEDED"));
        assertThat(documentCount(caseId)).isEqualTo(10);
    }

    @Test
    void staffCannotUploadEvenWithAValidFile() throws Exception {
        UUID caseId = kyc.fullCase(ApiMockMvc.newSubject());

        mvc.perform(multipart("/api/v1/kyc/cases/{id}/documents", caseId)
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF)).param("type", "PAN_CARD")
                        .with(KycFlows.officer(ApiMockMvc.newSubject())))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/kyc/cases/{id}/documents", caseId)
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", PDF)).param("type", "PAN_CARD"))
                .andExpect(status().isUnauthorized());
        assertNothingStored(caseId);
    }

    private void assertNothingStored(UUID caseId) {
        assertThat(documentCount(caseId)).isZero();
        ListObjectsV2Response objects = s3.listObjectsV2(
                b -> b.bucket(TestcontainersConfiguration.KYC_BUCKET).prefix("kyc/" + caseId + "/"));
        assertThat(objects.contents()).as("objects in S3 for case %s", caseId).isEqualTo(List.of());
    }

    private int documentCount(UUID caseId) {
        return jdbc.queryForObject("select count(*) from kyc.kyc_documents where case_id = ?", Integer.class, caseId);
    }
}
