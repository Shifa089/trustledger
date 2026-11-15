package com.trustledger.core.kyc.domain;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Port for the document object store (S3 in every environment; LocalStack locally and in tests).
 * Implementations throw {@link com.trustledger.core.shared.TrustLedgerException} with
 * {@code STORAGE_UNAVAILABLE} when the store fails, never a vendor exception.
 */
public interface DocumentStorage {

    /**
     * Stores {@code content} under {@code key}, encrypted at rest. The store verifies the bytes it received
     * against {@code sha256Hex} and refuses the upload on a mismatch.
     */
    void put(String key, byte[] content, String contentType, String sha256Hex);

    /** Removes the object; a missing object is not an error. */
    void delete(String key);

    /**
     * A time-limited GET link for one object, served as a download with the given content type.
     * Nobody needs credentials to use it until it expires, so keep {@code ttl} short.
     */
    PresignedLink presignGet(String key, String contentType, Duration ttl);

    record PresignedLink(URI url, Instant expiresAt) {
    }
}
