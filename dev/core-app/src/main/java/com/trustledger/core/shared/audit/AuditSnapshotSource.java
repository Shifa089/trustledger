package com.trustledger.core.shared.audit;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI: a module tells the audit trail what one of its entities looks like, for the before/after of an
 * {@link Audited} action. One bean per entity type.
 * <p>
 * Return only what an auditor needs to follow a decision (status, who decided): IDs, codes and decimal-string
 * amounts. Never a credential (a presigned URL) and never free text. Personal data is masked again by the audit
 * trail, but should not be here in the first place. Values must be strings, booleans, integral numbers, maps or
 * lists (the canonical JSON refuses anything else).
 */
public interface AuditSnapshotSource {

    /** The entity type code this source describes, as in {@link Audited#entityType()}. */
    String entityType();

    /** The entity's current state; empty when it doesn't exist. */
    Optional<Map<String, Object>> snapshot(UUID id);
}
