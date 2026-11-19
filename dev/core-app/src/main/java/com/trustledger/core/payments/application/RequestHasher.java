package com.trustledger.core.payments.application;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request hash stored with an idempotency key: SHA-256 (lower-case hex) of the <em>canonical</em> JSON body.
 * <p>
 * Canonical means parsed and written again with object keys sorted (at every depth) and no whitespace, so
 * {@code {"a":1, "b":2}} and {@code {"b":2,"a":1}} are the same request, while any changed value is a different
 * one. Decimal numbers are kept as written ({@code 1.50} differs from {@code 1.5}); amounts are decimal strings
 * anyway (SPEC §6). An empty body hashes as the empty string.
 */
@Component
public class RequestHasher {

    private final JsonMapper mapper;

    public RequestHasher(JsonMapper jsonMapper) {
        this.mapper = jsonMapper.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }

    /** @throws TrustLedgerException {@code VALIDATION_FAILED} if the body is not valid JSON */
    public String hash(byte[] body) {
        return sha256Hex(canonical(body));
    }

    byte[] canonical(byte[] body) {
        if (body == null || body.length == 0) {
            return new byte[0];
        }
        try {
            Object tree = mapper.readValue(body, Object.class);
            return mapper.writeValueAsBytes(sorted(tree));
        } catch (JacksonException e) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "The request body must be valid JSON.");
        }
    }

    private static Object sorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), sorted(value)));
            return result;
        }
        if (node instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** For tests and logs: the canonical form as text. */
    String canonicalText(byte[] body) {
        return new String(canonical(body), StandardCharsets.UTF_8);
    }
}
