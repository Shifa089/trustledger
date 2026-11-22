package com.trustledger.core.audit.domain;

import java.util.Objects;

/** An audit event as stored: its place in the chain ({@code seq}) and the two hashes that link it. */
public record ChainedAuditEvent(long seq, AuditEvent event, String prevHash, String hash) {

    public ChainedAuditEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(hash, "hash");
    }
}
