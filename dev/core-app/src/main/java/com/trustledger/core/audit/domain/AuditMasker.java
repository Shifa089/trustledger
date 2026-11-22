package com.trustledger.core.audit.domain;

import com.trustledger.core.shared.masking.Masking;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Masks personal data in an entry's before/after state (FR-A-1 "masked before/after", FR-PR-1), at any depth.
 * <p>
 * A safety net: today's domain events carry IDs, amounts and statuses only. A value is recognised by its key
 * (case and separators ignored, so {@code fullName}, {@code full_name} and {@code FULL-NAME} are the same key); a
 * value of the wrong shape under such a key, such as an address object, is fully masked.
 */
public final class AuditMasker {

    private static final UnaryOperator<String> FULLY = value -> Masking.FULLY_MASKED;

    private static final Map<String, UnaryOperator<String>> MASKS = Map.ofEntries(
            Map.entry("pan", Masking::pan),
            Map.entry("phone", Masking::phone),
            Map.entry("phonenumber", Masking::phone),
            Map.entry("mobile", Masking::phone),
            Map.entry("mobilenumber", Masking::phone),
            Map.entry("email", Masking::email),
            Map.entry("emailaddress", Masking::email),
            Map.entry("name", Masking::name),
            Map.entry("fullname", Masking::name),
            Map.entry("dob", FULLY),
            Map.entry("dateofbirth", FULLY),
            Map.entry("address", FULLY));

    private AuditMasker() {
    }

    public static AuditEntry mask(AuditEntry entry) {
        return new AuditEntry(entry.action(), entry.entityType(), entry.entityId(), maskMap(entry.before()),
                maskMap(entry.after()));
    }

    static Map<String, Object> maskMap(Map<String, ?> map) {
        if (map == null) {
            return null;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        map.forEach((key, value) -> masked.put(key, maskValue(key, value)));
        return masked;
    }

    private static Object maskValue(String key, Object value) {
        UnaryOperator<String> mask = MASKS.get(normalise(key));
        if (value instanceof List<?> list) {
            // Each item is judged under the list's key: a list of names is a list of masked names.
            return maskList(key, list);
        }
        if (mask != null) {
            if (value == null) {
                return null;
            }
            return value instanceof String text ? mask.apply(text) : Masking.FULLY_MASKED;
        }
        return switch (value) {
            case Map<?, ?> nested -> maskMap(stringKeys(nested));
            case null, default -> value;
        };
    }

    private static List<Object> maskList(String key, List<?> list) {
        List<Object> masked = new ArrayList<>(list.size());
        list.forEach(item -> masked.add(maskValue(key, item)));
        return masked;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static String normalise(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
    }
}
