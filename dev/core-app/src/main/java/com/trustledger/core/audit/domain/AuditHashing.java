package com.trustledger.core.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The hash chain (FR-A-1, ADR 0008): {@code hash = SHA-256(prevHash + canonicalJson)} over UTF-8, as 64 lower-case
 * hex characters. The first event's {@code prevHash} is {@link #GENESIS_HASH}.
 */
public final class AuditHashing {

    /** The predecessor of the first event: 64 zeros, a value no SHA-256 output is expected to take. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private AuditHashing() {
    }

    public static String hash(String prevHash, AuditEvent event) {
        Objects.requireNonNull(event, "event");
        if (prevHash == null || !HASH.matcher(prevHash).matches()) {
            throw new IllegalArgumentException("prevHash must be 64 lower-case hex characters");
        }
        return sha256Hex(prevHash + event.canonicalJson());
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
