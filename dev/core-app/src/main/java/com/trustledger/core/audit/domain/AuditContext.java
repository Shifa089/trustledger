package com.trustledger.core.audit.domain;

import java.util.Objects;

/**
 * Who acted and where the request came from, taken from the thread that is committing the business change.
 *
 * @param ip            the client address of the current HTTP request; {@code null} outside a request
 * @param correlationId the request's correlation ID; {@code null} outside a request
 */
public record AuditContext(AuditActor actor, String ip, String correlationId) {

    public static final AuditContext SYSTEM = new AuditContext(AuditActor.SYSTEM, null, null);

    public AuditContext {
        Objects.requireNonNull(actor, "actor");
    }

    /** The same context with the domain event's correlation ID, when it carries one. */
    public AuditContext withCorrelationId(String eventCorrelationId) {
        return eventCorrelationId == null ? this : new AuditContext(actor, ip, eventCorrelationId);
    }
}
