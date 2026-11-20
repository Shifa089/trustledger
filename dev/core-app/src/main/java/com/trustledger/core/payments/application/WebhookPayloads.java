package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.shared.Money;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a webhook body for <em>storage</em>: the event type, and the JSON with personal fields removed. The
 * signature is always checked over the raw bytes, never over this re-serialised JSON.
 */
@Component
class WebhookPayloads {

    /**
     * Removed wherever they appear: the payer's e-mail, phone, UPI ID, card and bank details, addresses. We never
     * need them, and keeping them would make this table personal data (CLAUDE.md, privacy module).
     */
    static final Set<String> PERSONAL_FIELDS = Set.of("email", "contact", "vpa", "card", "bank_account",
            "customer_details", "billing_address", "shipping_address");

    /** Razorpay's dotted lower-case types, e.g. {@code payment.captured}, {@code refund.processed}. */
    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z0-9_]{1,30}(\\.[a-z0-9_]{1,30}){0,3}");
    private static final int MAX_TYPE_LENGTH = 64;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** Gateway error codes such as {@code BAD_REQUEST_ERROR}; anything else is dropped. */
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z_]{1,64}");

    /**
     * @param eventType the body's {@code "event"} if it is a well-formed type, else null
     * @param json      the body as JSON with {@link #PERSONAL_FIELDS} removed
     */
    record Parsed(String eventType, String json) {
    }

    private final JsonMapper jsonMapper;

    WebhookPayloads(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** Empty when the body is not a JSON object, or can't be stored as jsonb. */
    Optional<Parsed> parse(byte[] body) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        redact(root);
        String json = jsonMapper.writeValueAsString(root);
        if (json.contains("\\u0000")) {
            return Optional.empty(); // Postgres jsonb refuses the NUL character
        }
        JsonNode event = root.get("event");
        String type = event != null && event.isString() && event.stringValue().length() <= MAX_TYPE_LENGTH
                && EVENT_TYPE.matcher(event.stringValue()).matches() ? event.stringValue() : null;
        return Optional.of(new Parsed(type, json));
    }

    /**
     * Reads the payment of a stored {@code payment.*} event: {@code payload.payment.entity} with {@code id},
     * {@code order_id}, {@code amount} (integer paise), {@code currency} and {@code status}. Only payments made on a
     * gateway order are returned, since only those can belong to a top-up.
     *
     * @param json the stored (already redacted) payload
     * @return empty when a field is missing or malformed
     */
    Optional<GatewayPayment> payment(String json) {
        JsonNode entity;
        try {
            entity = jsonMapper.readTree(json).path("payload").path("payment").path("entity");
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
        String paymentId = text(entity, "id");
        String orderId = text(entity, "order_id");
        String currency = text(entity, "currency");
        JsonNode amount = entity.path("amount");
        if (paymentId == null || !PaymentGateway.PAYMENT_ID.matcher(paymentId).matches()
                || orderId == null || !PaymentGateway.ORDER_ID.matcher(orderId).matches()
                || currency == null || !CURRENCY.matcher(currency).matches()
                || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() <= 0) {
            return Optional.empty();
        }
        Currency parsedCurrency;
        try {
            parsedCurrency = Currency.getInstance(currency);
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        String errorCode = text(entity, "error_code");
        if (errorCode != null && !ERROR_CODE.matcher(errorCode).matches()) {
            errorCode = null;
        }
        return Optional.of(new GatewayPayment(paymentId, orderId, Money.of(amount.longValue(), parsedCurrency),
                GatewayPaymentStatus.fromGateway(text(entity, "status")), errorCode));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(PERSONAL_FIELDS);
        }
        for (JsonNode child : node.values()) {
            redact(child);
        }
    }
}
