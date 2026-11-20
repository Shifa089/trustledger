package com.trustledger.core.payments.web;

import com.trustledger.core.payments.application.PaymentsProperties;
import com.trustledger.core.payments.application.WebhookService;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhooks/razorpay} (SPEC §5, FR-P-5). <strong>Public</strong>: Razorpay sends no token, so the HMAC
 * signature over the raw body is the authentication ({@link WebhookService}).
 * <p>
 * The body is read straight from the request stream as bytes, never through Jackson: the signature covers the exact
 * bytes Razorpay sent, and any re-serialisation (key order, spacing, number format) would break it. The size is
 * capped before anything is stored (413), because this endpoint stores every delivery, accepted or not.
 * <p>
 * Answers 200 with no body for processed, ignored and duplicate events; 400 for a bad signature or a malformed
 * delivery; 500 if processing failed, so Razorpay retries.
 */
@RestController
class RazorpayWebhookController {

    static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    static final String EVENT_ID_HEADER = "X-Razorpay-Event-Id";

    private final WebhookService webhooks;
    private final int maxBodyBytes;

    RazorpayWebhookController(WebhookService webhooks, PaymentsProperties properties) {
        this.webhooks = webhooks;
        this.maxBodyBytes = Math.toIntExact(properties.webhooks().maxBodySize().toBytes());
    }

    @SecurityRequirements // no bearer token: public, HMAC-verified
    @PostMapping(path = "/webhooks/razorpay", version = "1")
    ResponseEntity<Void> receive(HttpServletRequest request,
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(name = EVENT_ID_HEADER, required = false) String eventId) throws IOException {
        webhooks.receive(readBody(request), signature, eventId);
        return ResponseEntity.ok().build();
    }

    /** Reads at most the limit plus one byte, so an oversized body is never held in memory in full. */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            throw tooLarge();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) {
                throw tooLarge();
            }
            return body;
        }
    }

    private TrustLedgerException tooLarge() {
        return new TrustLedgerException(ErrorCode.PAYLOAD_TOO_LARGE,
                "The webhook body is larger than " + maxBodyBytes + " bytes.");
    }
}
