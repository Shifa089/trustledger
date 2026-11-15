package com.trustledger.core.kyc;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.ApiMockMvc.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.UserView;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/** {@code POST /api/v1/kyc/min} and {@code GET /api/v1/kyc/me} end to end (FR-KYC-1, FR-PR-1). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MinKycIT {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String PAN = "ABCPE1234F";
    private static final String NAME = "Kyc Test Person";
    private static final LocalDate ADULT_DOB = LocalDate.of(1995, 4, 15);

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdentityApi identityApi;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    @Test
    void validDetailsAreApprovedAtOnceAndStoredEncrypted() throws Exception {
        String subject = ApiMockMvc.newSubject();
        String phone = randomPhone();

        String body = submit(subject, NAME, ADULT_DOB.toString(), phone, PAN)
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.level").value("MIN"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.maskedPan").value("XXXXX1234X"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PAN, phone, NAME, "1995");

        UUID userId = identityApi.findUserId(subject).orElseThrow();
        Map<String, Object> row = jdbc.queryForMap("select * from kyc.kyc_cases where user_id = ?", userId);
        assertThat((String) row.get("pan_enc")).isNotBlank().doesNotContain(PAN, "1234");
        assertThat((String) row.get("dob_enc")).isNotBlank().doesNotContain("1995");
        assertThat((String) row.get("full_name_enc")).isNotBlank().doesNotContain("Kyc", "Person");
        assertThat((String) row.get("pan_hash")).matches("[0-9a-f]{64}");
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("verified_by")).isNull();
        assertThat(row.get("approved_by")).isNull();
        assertThat(row.get("approved_at")).isNotNull();
        Integer plaintextHits = jdbc.queryForObject(
                "select count(*) from kyc.kyc_cases k where k::text ilike ?", Integer.class, "%" + PAN + "%");
        assertThat(plaintextHits).isZero();

        // The verified mobile is now on the user, found through the blind index.
        assertThat(identityApi.findByPhone(phone)).get().extracting(UserView::id).isEqualTo(userId);
    }

    @Test
    void kycMeShowsNoneBeforeAndMinAfter() throws Exception {
        String subject = ApiMockMvc.newSubject();
        mvc.perform(get("/api/v1/kyc/me").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value("NONE"))
                .andExpect(jsonPath("$.cases", hasSize(0)));

        submit(subject, NAME, ADULT_DOB.toString(), randomPhone(), PAN).andExpect(status().isCreated());

        String body = mvc.perform(get("/api/v1/kyc/me").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value("MIN"))
                .andExpect(jsonPath("$.cases", hasSize(1)))
                .andExpect(jsonPath("$.cases[0].level").value("MIN"))
                .andExpect(jsonPath("$.cases[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.cases[0].maskedPan").value("XXXXX1234X"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PAN);
    }

    @Test
    void usersOnlySeeTheirOwnCases() throws Exception {
        String alice = ApiMockMvc.newSubject();
        submit(alice, NAME, ADULT_DOB.toString(), randomPhone(), PAN).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/kyc/me").with(customer(ApiMockMvc.newSubject())))
                .andExpect(jsonPath("$.currentLevel").value("NONE"))
                .andExpect(jsonPath("$.cases", hasSize(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCDE123F", "abcpe1234f", "ABCXE1234F", "1234567890"})
    void invalidPanIs400AndStoresNothing(String pan) throws Exception {
        String subject = ApiMockMvc.newSubject();

        submit(subject, NAME, ADULT_DOB.toString(), randomPhone(), pan)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("pan"));

        assertNoCase(subject);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5876543210", "98765", "+919876543210", "98765432101"})
    void invalidMobileIs400(String mobile) throws Exception {
        String subject = ApiMockMvc.newSubject();

        submit(subject, NAME, ADULT_DOB.toString(), mobile, PAN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("mobile"));

        assertNoCase(subject);
    }

    @Test
    void missingFieldsAndFutureDateAre400() throws Exception {
        mvc.perform(post("/api/v1/kyc/min").with(customer(ApiMockMvc.newSubject()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", hasSize(4)));

        submit(ApiMockMvc.newSubject(), NAME, LocalDate.now(INDIA).plusDays(1).toString(), randomPhone(), PAN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("dateOfBirth"));
    }

    @Test
    void underEighteenIs422AndNothingIsStored() throws Exception {
        String subject = ApiMockMvc.newSubject();
        String phone = randomPhone();
        LocalDate oneDayShort = LocalDate.now(INDIA).minusYears(18).plusDays(1);

        submit(subject, NAME, oneDayShort.toString(), phone, PAN)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("AGE_REQUIREMENT_NOT_MET"));

        assertNoCase(subject);
        assertThat(identityApi.findByPhone(phone)).isEmpty();
    }

    @Test
    void exactlyEighteenTodayIsAccepted() throws Exception {
        submit(ApiMockMvc.newSubject(), NAME, LocalDate.now(INDIA).minusYears(18).toString(), randomPhone(), PAN)
                .andExpect(status().isCreated());
    }

    @Test
    void secondSubmissionIs409AndKeepsOneCase() throws Exception {
        String subject = ApiMockMvc.newSubject();
        submit(subject, NAME, ADULT_DOB.toString(), randomPhone(), PAN).andExpect(status().isCreated());

        submit(subject, NAME, ADULT_DOB.toString(), randomPhone(), PAN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KYC_ALREADY_SUBMITTED"));

        assertThat(caseCount(subject)).isEqualTo(1);
    }

    @Test
    void phoneOfAnotherUserIs409AndTheCaseIsRolledBack() throws Exception {
        String phone = randomPhone();
        submit(ApiMockMvc.newSubject(), NAME, ADULT_DOB.toString(), phone, PAN).andExpect(status().isCreated());
        String second = ApiMockMvc.newSubject();

        submit(second, "Other Person", ADULT_DOB.toString(), phone, "ZZZPZ9999Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PHONE_ALREADY_REGISTERED"));

        assertNoCase(second);
    }

    @Test
    void concurrentSubmissionsOfOneUserCreateExactlyOneCase() throws Exception {
        String subject = ApiMockMvc.newSubject();
        String phone = randomPhone();
        // Provision the user first, so the race is about KYC only.
        mvc.perform(get("/api/v1/kyc/me").with(customer(subject))).andExpect(status().isOk());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return submit(subject, NAME, ADULT_DOB.toString(), phone, PAN).andReturn().getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
        }
        assertThat(caseCount(subject)).isEqualTo(1);
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mvc.perform(post("/api/v1/kyc/min").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/kyc/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void staffGet403EvenWithAnInvalidBody() throws Exception {
        for (String role : new String[] {"COMPLIANCE_OFFICER", "ADMIN", "AUDITOR"}) {
            mvc.perform(post("/api/v1/kyc/min")
                            .with(token(ApiMockMvc.newSubject(), "staff@example.test", "Staff Person", role))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        }
    }

    private ResultActions submit(String subject, String name, String dob, String mobile, String pan) throws Exception {
        String json = """
                {"fullName":"%s","dateOfBirth":"%s","mobile":"%s","pan":"%s"}""".formatted(name, dob, mobile, pan);
        return mvc.perform(post("/api/v1/kyc/min").with(customer(subject))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void assertNoCase(String subject) {
        assertThat(caseCount(subject)).isZero();
    }

    private int caseCount(String subject) {
        UUID userId = identityApi.findUserId(subject).orElseThrow();
        return jdbc.queryForObject("select count(*) from kyc.kyc_cases where user_id = ?", Integer.class, userId);
    }

    private static String randomPhone() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }
}
