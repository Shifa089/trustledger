package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.StoredAuditEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * One audit event as an auditor sees it: the stored row as is, hashes included, so the auditor can check a link
 * by hand. {@code before}/{@code after} were masked when written (FR-PR-1).
 */
public record AuditEventView(long seq, Instant occurredAt, UUID actorId, String actorRole, String action,
        String entityType, UUID entityId, Object before, Object after, String ip, String userAgent,
        String correlationId, String prevHash, String hash) {

    static AuditEventView of(StoredAuditEvent row) {
        return new AuditEventView(row.seq(), row.occurredAt(), row.actorId(), row.actorRole(), row.action(),
                row.entityType(), row.entityId(), row.before(), row.after(), row.ip(), row.userAgent(),
                row.correlationId(), row.prevHash(), row.hash());
    }
}
