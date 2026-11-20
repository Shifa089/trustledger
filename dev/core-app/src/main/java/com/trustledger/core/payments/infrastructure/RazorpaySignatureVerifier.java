package com.trustledger.core.payments.infrastructure;

import com.trustledger.core.payments.domain.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Razorpay's webhook signature: {@code X-Razorpay-Signature} = hex(HMAC-SHA256(raw body, webhook secret)).
 * <p>
 * The header is decoded to bytes and compared with {@link MessageDigest#isEqual}, which takes the same time whatever
 * the bytes are. A comparison that stops at the first wrong byte ({@code String.equals}) would let an attacker
 * learn the right signature one byte at a time by measuring response times.
 */
@Component
class RazorpaySignatureVerifier implements WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    /** 32 bytes of HMAC-SHA256 as hex. */
    private static final int SIGNATURE_HEX_LENGTH = 64;

    private final SecretKeySpec key;

    @Autowired
    RazorpaySignatureVerifier(RazorpayProperties properties) {
        this.key = keyOf(properties.webhookSecret());
    }

    private RazorpaySignatureVerifier(SecretKeySpec key) {
        this.key = key;
    }

    /** For unit tests, without building the whole properties record. */
    static RazorpaySignatureVerifier forSecret(String webhookSecret) {
        return new RazorpaySignatureVerifier(keyOf(webhookSecret));
    }

    private static SecretKeySpec keyOf(String webhookSecret) {
        Objects.requireNonNull(webhookSecret, "webhookSecret");
        return new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public boolean isValid(byte[] rawBody, String signature) {
        if (rawBody == null || signature == null || signature.length() != SIGNATURE_HEX_LENGTH) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signature); // accepts upper and lower case
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(hmac(rawBody), received);
    }

    /** Hex HMAC of the body; tests use it to sign their requests. */
    String sign(byte[] rawBody) {
        return HexFormat.of().formatHex(hmac(rawBody));
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM); // Mac is not thread-safe: one per call
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
