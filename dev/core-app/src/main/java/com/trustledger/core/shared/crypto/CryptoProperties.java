package com.trustledger.core.shared.crypto;

import java.util.Arrays;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keys for field encryption and blind indexes, both base64-encoded 32-byte (256-bit) values.
 * Validated in the constructor, so the application refuses to start with a missing or weak key.
 * <p>
 * {@link #toString()} is overridden so the keys never end up in logs or actuator output.
 */
@ConfigurationProperties("trustledger.crypto")
public record CryptoProperties(String key, String indexKey) {

    static final int KEY_BYTES = 32;

    public CryptoProperties {
        byte[] encryption = decode("trustledger.crypto.key", key);
        byte[] index = decode("trustledger.crypto.index-key", indexKey);
        if (Arrays.equals(encryption, index)) {
            throw new IllegalArgumentException(
                    "trustledger.crypto.key and trustledger.crypto.index-key must be different keys");
        }
    }

    byte[] encryptionKeyBytes() {
        return Base64.getDecoder().decode(key.strip());
    }

    byte[] indexKeyBytes() {
        return Base64.getDecoder().decode(indexKey.strip());
    }

    private static byte[] decode(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required (base64, 32 bytes)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not valid base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(name + " must decode to " + KEY_BYTES + " bytes, got " + bytes.length);
        }
        return bytes;
    }

    @Override
    public String toString() {
        return "CryptoProperties[key=****, indexKey=****]";
    }
}
