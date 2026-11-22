package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.shared.audit.AuditSnapshotSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit event of one {@code @Audited} endpoint call (FR-A-1, ADR 0008): the state of the entity before
 * and after, from the owning module's {@link AuditSnapshotSource}, and the outcome.
 * <p>
 * Called by the aspect around the controller, <b>after</b> the business transaction has ended, so each record
 * runs in a transaction of its own. The domain facts of the same call (WALLET_FROZEN, TRANSFER_COMPLETED,
 * KYC_LEVEL_UPGRADED) are written atomically in the business transaction by {@link AuditListeners}; this row
 * records the request itself, refused attempts included.
 */
@Service
public class AuditedActionRecorder {

    /** Keys the recorder adds to {@code after}; a snapshot or detail can't overwrite them. */
    static final String OUTCOME = "outcome";
    static final String ERROR_CODE = "errorCode";

    public enum Outcome {
        SUCCEEDED,
        /** Refused by a business rule ({@code TrustLedgerException}); nothing changed. */
        REJECTED
    }

    private final Map<String, AuditSnapshotSource> sources;
    private final AuditWriter writer;
    private final AuditContextProvider context;

    public AuditedActionRecorder(List<AuditSnapshotSource> sources, AuditWriter writer,
            AuditContextProvider context) {
        Map<String, AuditSnapshotSource> byType = new HashMap<>();
        for (AuditSnapshotSource source : sources) {
            if (byType.putIfAbsent(source.entityType(), source) != null) {
                throw new IllegalStateException("Two audit snapshot sources for " + source.entityType());
            }
        }
        this.sources = Map.copyOf(byType);
        this.writer = writer;
        this.context = context;
    }

    /**
     * The entity's current state, or {@code null} when it doesn't exist or no module describes its type (a
     * reconciliation run). Runs in the owning module's read-only transaction.
     */
    public Map<String, Object> snapshot(AuditEntityType entityType, UUID entityId) {
        AuditSnapshotSource source = sources.get(entityType.name());
        if (source == null) {
            return null;
        }
        return source.snapshot(entityId).orElse(null);
    }

    /** {@code after} = the state now, plus the call's details, plus {@code "outcome": "SUCCEEDED"}. */
    @Transactional
    public void succeeded(AuditAction action, AuditEntityType entityType, UUID entityId, Map<String, Object> before,
            Map<String, Object> details) {
        Map<String, Object> after = new LinkedHashMap<>();
        Map<String, Object> now = snapshot(entityType, entityId);
        if (now != null) {
            after.putAll(now);
        }
        after.putAll(details);
        after.put(OUTCOME, Outcome.SUCCEEDED.name());
        write(new AuditEntry(action, entityType, entityId, before, after));
    }

    /** {@code after} = the call's details, plus {@code "outcome": "REJECTED"} and the error code. */
    @Transactional
    public void rejected(AuditAction action, AuditEntityType entityType, UUID entityId, Map<String, Object> before,
            Map<String, Object> details, String errorCode) {
        Map<String, Object> after = new LinkedHashMap<>(details);
        after.put(OUTCOME, Outcome.REJECTED.name());
        after.put(ERROR_CODE, errorCode);
        write(new AuditEntry(action, entityType, entityId, before, after));
    }

    private void write(AuditEntry entry) {
        writer.write(entry, context.current());
    }
}
