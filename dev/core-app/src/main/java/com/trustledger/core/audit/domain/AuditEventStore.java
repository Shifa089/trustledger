package com.trustledger.core.audit.domain;

import java.time.Instant;
import java.util.List;
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

    /** Rows matching the filter, newest first ({@code seq} descending). */
    List<StoredAuditEvent> search(AuditEventFilter filter, long offset, int limit);

    long count(AuditEventFilter filter);

    /** Up to {@code limit} rows with {@code seq > afterSeq}, in {@code seq} order: the next batch of the chain. */
    List<StoredAuditEvent> chainAfter(long afterSeq, int limit);
}
