package com.trustledger.core.payments.application;

import com.trustledger.core.payments.application.WebhookPayloads.Parsed;
import com.trustledger.core.payments.domain.WebhookEvent;
import com.trustledger.core.payments.domain.WebhookSignatureVerifier;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives a gateway webhook delivery (FR-P-5). Every delivery is stored, accepted or not:
 * <ul>
 * <li>signature doesn't match the raw body → stored as rejected, {@code WEBHOOK_SIGNATURE_INVALID} (400), nothing
 * processed;</li>
 * <li>signed but without an event ID, a JSON object or an event type → stored as rejected,
 * {@code VALIDATION_FAILED} (400);</li>
 * <li>otherwise stored as accepted (one row per gateway event ID; a redelivery inserts nothing), then
 * {@link WebhookProcessor} runs in its own transaction: processed, ignored (unknown type) or duplicate, all 200.</li>
 * </ul>
 * Runs with no transaction of its own ({@code NEVER}): the stored row must be committed before processing starts, so
 * a processing failure can't erase the record that the delivery arrived.
 * <p>
 * Logs only the event ID (validated against a strict pattern) and type; never the body, which may hold personal
 * data.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    /** Razorpay event IDs are short alphanumeric strings; anything else isn't stored or logged. */
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final WebhookSignatureVerifier verifier;
    private final WebhookPayloads payloads;
    private final WebhookWriter writer;
    private final WebhookProcessor processor;
    private final Clock clock;

    WebhookService(WebhookSignatureVerifier verifier, WebhookPayloads payloads, WebhookWriter writer,
            WebhookProcessor processor, Clock clock) {
        this.verifier = verifier;
        this.payloads = payloads;
        this.writer = writer;
        this.processor = processor;
        this.clock = clock;
    }

    /**
     * @param rawBody       the request body exactly as received
     * @param signature     the {@code X-Razorpay-Signature} header, may be null
     * @param eventIdHeader the {@code X-Razorpay-Event-Id} header, may be null
     * @throws TrustLedgerException {@code WEBHOOK_SIGNATURE_INVALID} or {@code VALIDATION_FAILED}; the delivery is
     *         stored as rejected in both cases
     */
    @Transactional(propagation = Propagation.NEVER)
    public WebhookOutcome receive(byte[] rawBody, String signature, String eventIdHeader) {
        Objects.requireNonNull(rawBody, "rawBody");
        Instant now = clock.instant();
        String eventId = eventIdHeader != null && EVENT_ID.matcher(eventIdHeader).matches() ? eventIdHeader : null;
        Optional<Parsed> parsed = payloads.parse(rawBody);
        String type = parsed.map(Parsed::eventType).orElse(null);
        String payload = parsed.map(Parsed::json).orElse(null);

        if (!verifier.isValid(rawBody, signature)) {
            writer.storeRejected(WebhookEvent.rejected(UUID.randomUUID(), eventId, type, payload, now));
            log.warn("Rejected a Razorpay webhook: invalid signature (event {}, type {})", eventId, type);
            throw new TrustLedgerException(ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                    "The webhook signature does not match the request body.");
        }
        if (eventId == null || type == null || payload == null) {
            writer.storeRejected(WebhookEvent.rejected(UUID.randomUUID(), eventId, type, payload, now));
            log.warn("Rejected a signed Razorpay webhook: missing event ID, JSON body or event type (event {})",
                    eventId);
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "A webhook needs an X-Razorpay-Event-Id header and a JSON body with an event type.");
        }

        writer.storeAccepted(WebhookEvent.accepted(UUID.randomUUID(), eventId, type, payload, now));
        WebhookOutcome outcome = processor.process(eventId);
        log.info("Razorpay webhook {} ({}): {}", eventId, type, outcome);
        return outcome;
    }
}
