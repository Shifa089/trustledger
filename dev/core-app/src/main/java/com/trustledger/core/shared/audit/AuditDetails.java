package com.trustledger.core.shared.audit;

import java.util.Map;

/**
 * Implemented by a request or response record of an {@link Audited} endpoint to add facts to the audit event's
 * {@code after} state, such as the length of a free-text reason (never the text) or a run's counts. The same
 * value rules as {@link AuditSnapshotSource} apply.
 */
public interface AuditDetails {

    Map<String, Object> auditDetails();
}
