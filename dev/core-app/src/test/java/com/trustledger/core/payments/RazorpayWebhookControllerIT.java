package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code POST /api/v1/webhooks/razorpay} through the production filter chain and Postgres (FR-P-5): signature over
 * the raw body, every delivery stored, de-duplication by event ID among accepted events, processing in its own
 * transaction, unknown types stored and ignored.
 * <p>
 * Requests are signed here with the test secret from {@code config/application.yml}, computed independently of the
 * production verifier. {@link RecordingWebhookHandler} counts how often an event of type {@code test.recorded} was
 * really processed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RazorpayWebhookControllerIT {

    private static final String URL = "/api/v1/webhooks/razorpay";
    private static final String SECRET = "fake-webhook-secret-for-tests";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingWebhookHandler handler;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    // --- helpers ---

    private static String newEventId() {
        return "Evt" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static String body(String type) {
        return """
                {"entity":"event","account_id":"acc_FAKE","event":"%s","contains":["payment"],
                 "payload":{"payment":{"entity":{"id":"pay_FAKE0000000001","order_id":"order_FAKE000000001",
                 "amount":50000,"currency":"INR","status":"captured","email":"customer.one@example.test",
                 "contact":"+919999999999"}}},"created_at":1763632800}""".formatted(type);
    }

    private static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockHttpServletRequestBuilder delivery(String eventId, String body, String signature) {
        MockHttpServletRequestBuilder request = post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            request.header("X-Razorpay-Event-Id", eventId);
        }
        if (signature != null) {
            request.header("X-Razorpay-Signature", signature);
        }
        return request;
    }

    private static MockHttpServletRequestBuilder signed(String eventId, String body) {
        return delivery(eventId, body, sign(body.getBytes(StandardCharsets.UTF_8)));
    }

    private List<Map<String, Object>> rows(String eventId) {
        return jdbc.queryForList("""
                select signature_valid, event_type, payload::text as payload, received_at, processed_at
                from payments.webhook_events where gateway_event_id = ?
                order by signature_valid, received_at""", eventId);
    }

    private long acceptedRows(String eventId) {
        return jdbc.queryForObject("""
                select count(*) from payments.webhook_events where gateway_event_id = ? and signature_valid""",
                Long.class, eventId);
    }

    private long rowCount() {
        return jdbc.queryForObject("select count(*) from payments.webhook_events", Long.class);
    }

    // --- accepted events ---

    @Test
    void validEventIsStoredProcessedOnceAndAnswered200() throws Exception {
        String eventId = newEventId();

        mvc.perform(signed(eventId, body(RecordingWebhookHandler.TYPE)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        assertThat(handler.timesHandled(eventId)).isEqualTo(1);
        List<Map<String, Object>> rows = rows(eventId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("signature_valid")).isEqualTo(true);
        assertThat(row.get("event_type")).isEqualTo(RecordingWebhookHandler.TYPE);
        assertThat(row.get("processed_at")).isNotNull();
        assertThat((String) row.get("payload")).contains("pay_FAKE0000000001")
                .doesNotContain("example.test", "+919999999999");
    }

    @Test
    void needsNoTokenAndNoCsrfToken() throws Exception {
        // signed(...) carries neither an Authorization header nor a CSRF token, like Razorpay's own requests.
        mvc.perform(signed(newEventId(), body(RecordingWebhookHandler.TYPE))).andExpect(status().isOk());
    }

    @Test
    void duplicateDeliveryIsAnswered200AndNotProcessedAgain() throws Exception {
        String eventId = newEventId();
        String body = body(RecordingWebhookHandler.TYPE);

        mvc.perform(signed(eventId, body)).andExpect(status().isOk());
        mvc.perform(signed(eventId, body)).andExpect(status().isOk());
        mvc.perform(signed(eventId, body)).andExpect(status().isOk());

        assertThat(handler.timesHandled(eventId)).isEqualTo(1);
        assertThat(acceptedRows(eventId)).isEqualTo(1);
    }

    @Test
    void unknownTypeIsStoredAndIgnored() throws Exception {
        String eventId = newEventId();

        mvc.perform(signed(eventId, body("refund.processed"))).andExpect(status().isOk());

        assertThat(handler.timesHandled(eventId)).isZero();
        Map<String, Object> row = rows(eventId).getFirst();
        assertThat(row.get("signature_valid")).isEqualTo(true);
        assertThat(row.get("event_type")).isEqualTo("refund.processed");
        assertThat(row.get("processed_at")).as("ignored events are marked processed").isNotNull();
    }

    // --- rejected deliveries ---

    @Test
    void invalidSignatureIsRejectedStoredAsInvalidAndNotProcessed() throws Exception {
        String eventId = newEventId();

        mvc.perform(delivery(eventId, body(RecordingWebhookHandler.TYPE), "0".repeat(64)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"))
                .andExpect(jsonPath("$.status").value(400));

        assertThat(handler.timesHandled(eventId)).isZero();
        List<Map<String, Object>> rows = rows(eventId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("signature_valid")).isEqualTo(false);
        assertThat(rows.getFirst().get("processed_at")).isNull();
    }

    @Test
    void missingSignatureIsRejectedAndStored() throws Exception {
        String eventId = newEventId();

        mvc.perform(delivery(eventId, body(RecordingWebhookHandler.TYPE), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"));

        assertThat(rows(eventId)).singleElement()
                .satisfies(row -> assertThat(row.get("signature_valid")).isEqualTo(false));
        assertThat(handler.timesHandled(eventId)).isZero();
    }

    /** The signature covers the bytes as sent: the same JSON re-formatted is not the signed message. */
    @Test
    void signatureOfReformattedJsonIsRejected() throws Exception {
        String eventId = newEventId();
        String original = body(RecordingWebhookHandler.TYPE);
        String reformatted = original.replace(",", ", ");

        mvc.perform(delivery(eventId, reformatted, sign(original.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest());

        assertThat(handler.timesHandled(eventId)).isZero();
    }

    @Test
    void signedEventWithoutEventIdIsRejectedAndStored() throws Exception {
        long before = rowCount();

        mvc.perform(signed(null, body(RecordingWebhookHandler.TYPE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        assertThat(rowCount()).isEqualTo(before + 1);
    }

    /**
     * The attack the partial unique index stops: someone who knows (or guesses) a real event's ID posts a forged
     * delivery first. The genuine delivery must still be processed, not dropped as a "duplicate".
     */
    @Test
    void forgedDeliveryWithARealEventIdDoesNotBlockTheGenuineOne() throws Exception {
        String eventId = newEventId();
        String body = body(RecordingWebhookHandler.TYPE);

        mvc.perform(delivery(eventId, body, "f".repeat(64))).andExpect(status().isBadRequest());
        mvc.perform(signed(eventId, body)).andExpect(status().isOk());

        assertThat(handler.timesHandled(eventId)).isEqualTo(1);
        assertThat(rows(eventId)).extracting(row -> row.get("signature_valid")).containsExactly(false, true);
    }

    @Test
    void bodyOverTheLimitIs413AndNotStored() throws Exception {
        long before = rowCount();
        String huge = "{\"event\":\"test.recorded\",\"pad\":\"" + "x".repeat(64 * 1024) + "\"}";

        mvc.perform(signed(newEventId(), huge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));

        assertThat(rowCount()).isEqualTo(before);
    }

    // --- processing in its own transaction ---

    @Test
    void processingFailureAnswers500KeepsTheStoredRowAndTheRetryProcessesIt() throws Exception {
        String eventId = newEventId();
        String body = body(RecordingWebhookHandler.TYPE);
        handler.failFor(eventId);
        try {
            mvc.perform(signed(eventId, body)).andExpect(status().isInternalServerError());

            assertThat(handler.timesHandled(eventId)).isZero();
            Map<String, Object> row = rows(eventId).getFirst();
            assertThat(row.get("signature_valid")).as("stored before processing, committed").isEqualTo(true);
            assertThat(row.get("processed_at")).as("processing rolled back").isNull();
        } finally {
            handler.succeedFor(eventId);
        }

        // Razorpay retries the same event: it is not a duplicate yet, so it is processed now.
        mvc.perform(signed(eventId, body)).andExpect(status().isOk());

        assertThat(handler.timesHandled(eventId)).isEqualTo(1);
        assertThat(acceptedRows(eventId)).isEqualTo(1);
        assertThat(rows(eventId).getFirst().get("processed_at")).isNotNull();
    }

    @Test
    void concurrentDeliveriesOfOneEventAreProcessedExactlyOnce() throws Exception {
        String eventId = newEventId();
        String body = body(RecordingWebhookHandler.TYPE);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> mvc.perform(signed(eventId, body)).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = ConcurrentRun.together(tasks);

        assertThat(statuses).containsOnly(200);
        assertThat(handler.timesHandled(eventId)).isEqualTo(1);
        assertThat(acceptedRows(eventId)).isEqualTo(1);
    }
}
