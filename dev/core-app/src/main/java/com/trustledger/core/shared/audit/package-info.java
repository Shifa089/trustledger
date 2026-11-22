/**
 * The audit trail's contract with the other modules (FR-A-1, M10): the {@link Audited} annotation and the
 * {@link AuditSnapshotSource} and {@link AuditDetails} SPIs. In the shared kernel so a module can be audited
 * without depending on the audit module, which in turn depends on no module's internals.
 */
package com.trustledger.core.shared.audit;
