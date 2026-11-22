package com.trustledger.core.audit.domain;

import java.time.Instant;
import java.util.Optional;

/** Port for {@code audit.audit_events}. Every method joins the caller's transaction. */
public interface AuditEventStore {

    /**
     * Takes the chain's transaction-scoped advisory lock: blocks until no other transaction holds it and keeps it
     * until this transaction ends. Serialises "read the last hash, insert the next row".
     */
    void lockChain();

    /** The hash of the last event in the chain (highest {@code seq}); empty for an empty table. */
    Optional<String> latestHash();

    /** Inserts the row and returns its {@code seq}. */
    long append(AuditEvent event, String prevHash, String hash, Instant createdAt);
}
