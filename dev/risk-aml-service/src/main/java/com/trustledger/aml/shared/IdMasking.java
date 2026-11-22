package com.trustledger.aml.shared;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Masks identifiers for exports that leave the service (the STR report, FR-AML-4): only the last 4 characters
 * stay, like the "last 4 characters of IDs" rule for e-mails (FR-N-1). Enough to match a line against the system,
 * too little to look anything up.
 */
public final class IdMasking {

    public static final String MASK = "****";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private IdMasking() {
    }

    /** {@code "0193...-c0ffee5a1b2c" -> "****1b2c"}; {@code null -> ""}. */
    public static String id(UUID id) {
        return id == null ? "" : lastFour(id.toString());
    }

    /** Masks every UUID inside free text, e.g. an alert's "latest transfer &lt;id&gt;". */
    public static String idsIn(String text) {
        if (text == null) {
            return "";
        }
        return UUID_PATTERN.matcher(text).replaceAll(match -> lastFour(match.group()));
    }

    private static String lastFour(String value) {
        return MASK + value.substring(value.length() - 4);
    }
}
