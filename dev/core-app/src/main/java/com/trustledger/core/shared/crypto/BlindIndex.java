package com.trustledger.core.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 "blind index" so encrypted values can still be looked up by equality
 * (e.g. find a user by phone) without storing them in clear.
 * <p>
 * Input is normalised first, so formatting differences still match, and prefixed with its field type,
 * so the same text in two different fields gives two different indexes. Output: 64 lowercase hex chars.
 */
@Component
public class BlindIndex {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    @Autowired
    public BlindIndex(CryptoProperties properties) {
        this(properties.indexKeyBytes());
    }

    BlindIndex(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /** Trims and lower-cases. */
    public String email(String email) {
        return hmac("email", requireText(email).strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Keeps digits only, then drops a leading country code {@code 91} (12 digits) or trunk {@code 0}
     * (11 digits). The result must be a 10-digit number.
     */
    public String phone(String phone) {
        String digits = requireText(phone).replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 10) {
            throw new IllegalArgumentException("Phone number must have 10 digits");
        }
        return hmac("phone", digits);
    }

    /** Generic index for other fields: trims only. {@code field} separates the index spaces. */
    public String value(String field, String value) {
        return hmac(requireText(field), requireText(value).strip());
    }

    private String hmac(String field, String normalised) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM); // Mac is not thread-safe; creating one is cheap
            mac.init(key);
            byte[] digest = mac.doFinal((field + ":" + normalised).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Value to index must not be blank");
        }
        return value;
    }
}
