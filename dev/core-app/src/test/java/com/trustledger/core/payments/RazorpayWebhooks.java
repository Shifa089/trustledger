package com.trustledger.core.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Builds signed Razorpay {@code payment.*} webhook deliveries for ITs, with the test secret from
 * {@code config/application.yml}. Signed independently of the production verifier. Fake values only.
 */
final class RazorpayWebhooks {

    static final String URL = "/api/v1/webhooks/razorpay";
    private static final String SECRET = "fake-webhook-secret-for-tests";

    private RazorpayWebhooks() {
    }

    static String newEventId() {
        return "Evt" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    static String newPaymentId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    /** A {@code payment.captured} or {@code payment.failed} body for the order, with fake personal fields. */
    static String paymentEvent(String type, String paymentId, String orderId, long amountMinor, String status) {
        return """
                {"entity":"event","account_id":"acc_FAKE","event":"%s","contains":["payment"],
                 "payload":{"payment":{"entity":{"id":"%s","entity":"payment","order_id":"%s","amount":%d,
                 "currency":"INR","status":"%s","method":"upi","email":"customer.one@example.test",
                 "contact":"+919999999999","vpa":"someone@upi"}}},"created_at":1763632800}"""
                .formatted(type, paymentId, orderId, amountMinor, status);
    }

    static String captured(String paymentId, String orderId, long amountMinor) {
        return paymentEvent("payment.captured", paymentId, orderId, amountMinor, "captured");
    }

    static String failed(String paymentId, String orderId, long amountMinor) {
        return paymentEvent("payment.failed", paymentId, orderId, amountMinor, "failed");
    }

    static MockHttpServletRequestBuilder signed(String eventId, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return post(URL).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Razorpay-Event-Id", eventId)
                .header("X-Razorpay-Signature", sign(bytes));
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
}
