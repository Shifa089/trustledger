package com.trustledger.core.audit.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The canonical JSON form that audit hashes are computed over (ADR 0008): object keys sorted (by UTF-16 code
 * units, {@link String#compareTo}) at every depth, no whitespace, {@code null} written out.
 * <p>
 * Hand-written on purpose: the hash must stay the same for years, so it must not change when a JSON library
 * changes its escaping or number formatting. Only the types that survive a {@code jsonb} round trip unchanged are
 * allowed: strings, booleans, integral numbers (written as {@code long}), maps with string keys, lists and
 * {@code null}. Fractional numbers are refused: amounts are decimal strings (SPEC §6), and a float's text form
 * is not stable.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /** @throws IllegalArgumentException for a value of an unsupported type */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> appendString(out, s);
            case Boolean b -> out.append(b.booleanValue());
            case Long l -> out.append(l.longValue());
            case Integer i -> out.append(i.longValue());
            case Short s -> out.append(s.longValue());
            case Byte b -> out.append(b.longValue());
            case Map<?, ?> map -> appendObject(out, map);
            case List<?> list -> appendArray(out, list);
            default -> throw new IllegalArgumentException(
                    "Unsupported type in audit JSON: " + value.getClass().getSimpleName());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((key, val) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("Audit JSON keys must be strings");
            }
            sorted.put(name, val);
        });
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, entry.getKey());
            out.append(':');
            append(out, entry.getValue());
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            append(out, list.get(i));
        }
        out.append(']');
    }

    /** RFC 8259 escaping: quote, backslash and control characters; everything else as is (UTF-8 when hashed). */
    private static void appendString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
