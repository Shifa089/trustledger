package com.trustledger.core.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM field encryption. Output is {@code base64(iv[12] || ciphertext || tag[16])}.
 * <p>
 * A fresh random IV per value means equal plaintexts give different ciphertexts, and GCM's tag
 * detects any modification. Thread-safe: {@link Cipher} is created per call, {@link SecureRandom}
 * is shared.
 */
@Component
public class AesGcmCipher {

    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AesGcmCipher(CryptoProperties properties) {
        this(properties.encryptionKeyBytes());
    }

    AesGcmCipher(byte[] keyBytes) {
        if (keyBytes.length != CryptoProperties.KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 needs a 32-byte key");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** @throws IllegalStateException if the value was tampered with, truncated or encrypted with another key */
    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length < IV_BYTES + TAG_BITS / 8) {
                throw new IllegalStateException("Decryption failed");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // No cause details: messages from the provider could hint at the data.
            throw new IllegalStateException("Decryption failed");
        }
    }
}
