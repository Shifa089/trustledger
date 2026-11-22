package com.trustledger.core.privacy.domain;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Port for the store of export files (S3 in every environment; LocalStack locally and in tests). Implementations
 * throw {@link com.trustledger.core.shared.TrustLedgerException} with {@code STORAGE_UNAVAILABLE} when the store
 * fails, never a vendor exception.
 */
public interface ExportStorage {

    /** Stores a JSON document under {@code key}, encrypted at rest; an existing object is replaced. */
    void putJson(String key, byte[] json);

    /**
     * A time-limited GET link for one export, served as a JSON download. Anyone holding it can download the file
     * until it expires, so keep {@code ttl} short. Signing is local: no call to the store.
     */
    PresignedLink presignGet(String key, Duration ttl);

    record PresignedLink(URI url, Instant expiresAt) {
    }
}
