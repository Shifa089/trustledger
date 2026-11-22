package com.trustledger.core.privacy.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Internal event: a data export was requested. Published in the request's transaction, so Spring Modulith stores it
 * in {@code event_publication} with the PENDING row; {@link DataExportListener} picks it up after the commit. Not a
 * public event (not in the module's base package), never externalized.
 */
public record DataExportRequested(UUID requestId, UUID userId) {

    public DataExportRequested {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(userId, "userId");
    }
}
