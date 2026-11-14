package com.trustledger.core.shared.masking;

import java.util.regex.Pattern;

/**
 * Masks personal data for responses and logs (FR-PR-1). Malformed input is fully masked, never
 * returned as-is, because a value that fails the format check may still be personal data.
 */
public final class Masking {

    public static final String FULLY_MASKED = "****";

    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");
    private static final Pattern PHONE = Pattern.compile("[0-9]{10}");

    private Masking() {
    }

    /** {@code ABCDE1234F -> XXXXX1234X}. */
    public static String pan(String pan) {
        if (pan == null) {
            return null;
        }
        return PAN.matcher(pan).matches() ? "XXXXX" + pan.substring(5, 9) + "X" : FULLY_MASKED;
    }

    /** {@code 9876543210 -> ******3210}. */
    public static String phone(String phone) {
        if (phone == null) {
            return null;
        }
        return PHONE.matcher(phone).matches() ? "******" + phone.substring(6) : FULLY_MASKED;
    }

    /**
     * {@code Test Customer One -> T*** C*** O***}: first letter of each word, three stars, so neither
     * word lengths nor the remaining letters are revealed. Blank input is fully masked.
     */
    public static String name(String fullName) {
        if (fullName == null) {
            return null;
        }
        String[] words = fullName.strip().split("\\s+");
        if (words[0].isEmpty()) {
            return FULLY_MASKED;
        }
        StringBuilder masked = new StringBuilder();
        for (String word : words) {
            if (!masked.isEmpty()) {
                masked.append(' ');
            }
            masked.appendCodePoint(word.codePointAt(0)).append("***");
        }
        return masked.toString();
    }

    /** {@code samiulla@domain.com -> s***@domain.com}. Always three stars, so the length is hidden. */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at < 1 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return FULLY_MASKED;
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
