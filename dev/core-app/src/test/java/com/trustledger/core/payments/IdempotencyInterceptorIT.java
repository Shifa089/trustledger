package com.trustledger.core.payments;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.IdentityApi;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code @Idempotent} end to end through the production filter chain (security, JIT provisioning, body caching),
 * using the test-only {@link IdempotencyProbeController}. Covers M6 "Done when" 1 at the HTTP level: the same key
 * twice runs the handler once and returns the same response; another body with the same key is 422.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyInterceptorIT {

    private static final String URL = "/api/v1/test/idempotency-probe";

    @Autowired
    WebApplicationContext context;

    @Autowired
    IdempotencyProbeController probe;

    @Autowired
    IdentityApi identity;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    MockMvc mvc;
    String subject;
    String ref;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        subject = ApiMockMvc.newSubject();
        ref = UUID.randomUUID().toString();
    }

    private String body(String outcome) {
        return "{\"ref\":\"" + ref + "\",\"amount\":\"100.00\",\"outcome\":\"" + outcome + "\"}";
    }

    private MockHttpServletRequestBuilder request(String key, String body, String sub) {
        MockHttpServletRequestBuilder builder = post(URL).with(customer(sub))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? builder : builder.header("Idempotency-Key", key);
    }

    private MockHttpServletResponse send(String key, String body) throws Exception {
        return mvc.perform(request(key, body, subject)).andReturn().getResponse();
    }

    private List<String> statuses(String key) {
        return jdbc.queryForList("select status from payments.idempotency_keys where idem_key = ?::uuid",
                String.class, key);
    }

    private JsonNode tree(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    // --- the header ---

    @Test
    void missingKeyIs400AndTheHandlerDoesNotRun() throws Exception {
        mvc.perform(request(null, body("OK"), subject))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("The Idempotency-Key header is required."));

        assertThat(probe.calls(ref)).isZero();
    }

    @Test
    void keyMustBeAUuid() throws Exception {
        mvc.perform(request("not-a-uuid", body("OK"), subject))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(request("  ", body("OK"), subject))
                .andExpect(status().isBadRequest());

        assertThat(probe.calls(ref)).isZero();
    }

    @Test
    void invalidJsonIs400AndLeavesNoKey() throws Exception {
        String key = UUID.randomUUID().toString();

        mvc.perform(request(key, "{\"ref\":", subject))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        assertThat(statuses(key)).isEmpty();
    }

    @Test
    void bodyAboveTheLimitIs400() throws Exception {
        String key = UUID.randomUUID().toString();
        String huge = "{\"ref\":\"" + ref + "\",\"amount\":\"" + "9".repeat(17 * 1024) + "\"}";

        mvc.perform(request(key, huge, subject))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is too large."));

        assertThat(probe.calls(ref)).isZero();
        assertThat(statuses(key)).isEmpty();
    }

    // --- replay and reuse ---

    @Test
    void sameKeyTwiceRunsOnceAndReturnsTheSameResponse() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = send(key, body("OK"));
        MockHttpServletResponse second = send(key, body("OK"));

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Idempotent-Replayed")).isNull();
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(tree(second)).isEqualTo(tree(first));
        assertThat(tree(first).get("call").asInt()).isEqualTo(1);
        assertThat(probe.calls(ref)).isEqualTo(1);
        assertThat(statuses(key)).containsExactly("COMPLETED");
    }

    @Test
    void reformattedBodyIsTheSameRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        send(key, body("OK"));

        String reordered = "{ \"outcome\" : \"OK\",\n \"amount\": \"100.00\", \"ref\": \"" + ref + "\" }";
        mvc.perform(request(key, reordered, subject))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(probe.calls(ref)).isEqualTo(1);
    }

    @Test
    void upperCaseKeyIsTheSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        send(key, body("OK"));

        mvc.perform(request(key.toUpperCase(), body("OK"), subject))
                .andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(probe.calls(ref)).isEqualTo(1);
    }

    @Test
    void sameKeyWithAnotherBodyIs422() throws Exception {
        String key = UUID.randomUUID().toString();
        send(key, body("OK"));

        mvc.perform(request(key, body("OK").replace("100.00", "100.01"), subject))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(probe.calls(ref)).isEqualTo(1);
    }

    @Test
    void keysArePerUser() throws Exception {
        String key = UUID.randomUUID().toString();
        String otherSubject = ApiMockMvc.newSubject();

        MockHttpServletResponse mine = send(key, body("OK"));
        MockHttpServletResponse theirs = mvc.perform(request(key, body("OK"), otherSubject)).andReturn().getResponse();

        assertThat(mine.getStatus()).isEqualTo(201);
        assertThat(theirs.getStatus()).isEqualTo(201);
        assertThat(theirs.getHeader("Idempotent-Replayed")).isNull();
        assertThat(probe.calls(ref)).isEqualTo(2);
        UUID myId = identity.findUserId(subject).orElseThrow();
        assertThat(jdbc.queryForObject(
                "select count(*) from payments.idempotency_keys where idem_key = ?::uuid and user_id = ?",
                Integer.class, key, myId)).isEqualTo(1);
    }

    // --- failures release the key ---

    @Test
    void businessRejectionIsNotStoredSoARetryRunsAgain() throws Exception {
        String key = UUID.randomUUID().toString();

        mvc.perform(request(key, body("REJECT"), subject))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));
        assertThat(statuses(key)).isEmpty();

        mvc.perform(request(key, body("REJECT"), subject))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(probe.calls(ref)).isEqualTo(2);
    }

    @Test
    void serverErrorReleasesTheKey() throws Exception {
        String key = UUID.randomUUID().toString();

        mvc.perform(request(key, body("CRASH"), subject))
                .andExpect(status().isInternalServerError());

        assertThat(statuses(key)).isEmpty();
    }

    // --- concurrency ---

    @Test
    void duplicateWhileTheFirstIsRunningIs409() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch inHandler = probe.closeGate(ref);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<MockHttpServletResponse> first = pool.submit(() -> send(key, body("OK")));
            assertThat(inHandler.await(10, TimeUnit.SECONDS)).isTrue();

            mvc.perform(request(key, body("OK"), subject))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_IN_PROGRESS"));

            probe.open(ref);
            assertThat(first.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
        }
        assertThat(probe.calls(ref)).isEqualTo(1);
        assertThat(statuses(key)).containsExactly("COMPLETED");
    }

    @Test
    void tenConcurrentDuplicatesRunTheHandlerOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        int threads = 10;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                responses.add(pool.submit(() -> {
                    go.await();
                    return send(key, body("OK"));
                }));
            }
            go.countDown();
        }

        List<Integer> codes = new ArrayList<>();
        for (Future<MockHttpServletResponse> response : responses) {
            codes.add(response.get().getStatus());
        }
        assertThat(probe.calls(ref)).isEqualTo(1);
        // Requests that overlap the first get 409; later ones get its replay. Either way the handler ran once.
        assertThat(codes).allMatch(code -> code == 201 || code == 409).contains(201);
        assertThat(statuses(key)).containsExactly("COMPLETED");
    }

    // --- other endpoints ---

    @Test
    void endpointsWithoutTheAnnotationIgnoreTheHeader() throws Exception {
        mvc.perform(get("/api/v1/me").with(customer(subject)).header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isOk());
    }
}
