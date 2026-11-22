package com.trustledger.core.audit.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What an action did to one entity, before masking: the part of an audit event that comes from the domain event.
 * Who did it and from where is the {@link AuditContext}.
 *
 * @param before the entity's relevant state before the action; {@code null} when it didn't exist or isn't known
 * @param after  its relevant state afterwards; {@code null} when it no longer exists
 */
public record AuditEntry(AuditAction action, AuditEntityType entityType, UUID entityId, Map<String, ?> before,
        Map<String, ?> after) {

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
    }
}
