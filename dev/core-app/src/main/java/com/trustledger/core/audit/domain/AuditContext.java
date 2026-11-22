package com.trustledger.core.audit.domain;

import java.util.Objects;

/**
 * Who acted and where the request came from, taken from the thread that is committing the business change.
 *
 * @param ip            the client address of the current HTTP request; {@code null} outside a request
 * @param correlationId the request's correlation ID; {@code null} outside a request
 * @param userAgent     the request's {@code User-Agent} header as sent; {@code null} outside a request or when
 *                      absent. Normalised (trimmed, control characters removed) by {@link AuditEvent}.
 */
public record AuditContext(AuditActor actor, String ip, String correlationId, String userAgent) {

    public static final AuditContext SYSTEM = new AuditContext(AuditActor.SYSTEM, null, null, null);

    public AuditContext {
        Objects.requireNonNull(actor, "actor");
    }

    /** Without a user agent (system work, tests). */
    public AuditContext(AuditActor actor, String ip, String correlationId) {
        this(actor, ip, correlationId, null);
    }

    /** The same context with the domain event's correlation ID, when it carries one. */
    public AuditContext withCorrelationId(String eventCorrelationId) {
        return eventCorrelationId == null ? this : new AuditContext(actor, ip, eventCorrelationId, userAgent);
    }
}
