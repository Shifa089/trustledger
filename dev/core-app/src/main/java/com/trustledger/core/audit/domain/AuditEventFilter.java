package com.trustledger.core.audit.domain;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filters of {@code GET /audit/events} (SPEC §5: entity, actor, date range). Every field is optional; the given
 * ones are combined with AND.
 *
 * @param from first {@code occurred_at} included
 * @param to   first {@code occurred_at} no longer included (half-open, so consecutive ranges don't overlap)
 * @throws TrustLedgerException {@code VALIDATION_FAILED} for a malformed code or {@code from} not before {@code to}
 */
public record AuditEventFilter(String entityType, UUID entityId, UUID actorId, String action, Instant from,
        Instant to) {

    private static final Pattern CODE = Pattern.compile("[A-Z_]{1,64}");

    public static final AuditEventFilter NONE = new AuditEventFilter(null, null, null, null, null, null);

    public AuditEventFilter {
        if (entityType != null && !CODE.matcher(entityType).matches()) {
            throw invalid("entityType must be an upper-case code such as WALLET.");
        }
        if (action != null && !CODE.matcher(action).matches()) {
            throw invalid("action must be an upper-case code such as WALLET_FREEZE.");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("from must be before to.");
        }
    }

    private static TrustLedgerException invalid(String detail) {
        return new TrustLedgerException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
