package com.trustledger.core.shared.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a compliance or admin endpoint whose every call is written to the audit trail (FR-A-1, M10, ADR 0008):
 * who (actor, role), from where (IP, user agent, correlation ID), what ({@link #action()} on one entity), the
 * entity's masked state before and after, and the outcome (SUCCEEDED, or REJECTED with the error code).
 * <p>
 * Lives in the shared kernel so a module can mark its controllers without depending on the audit module. The
 * audit module's aspect does the writing; the state comes from the module's {@link AuditSnapshotSource}.
 * Annotated methods must be {@code public} (Spring AOP proxies).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** An audit action code such as {@code KYC_CASE_VERIFY}; must exist in the audit module's action list. */
    String action();

    /** The entity type code, such as {@code KYC_CASE}; also selects the {@link AuditSnapshotSource}. */
    String entityType();

    /**
     * The name of the method parameter (a {@code UUID}) holding the entity's ID. Empty: the action creates its own
     * entity (a reconciliation run), and the aspect gives it a new ID.
     */
    String entityIdParam() default "id";

    /** Take a snapshot before the call too. Off for reads, where before and after are the same. */
    boolean captureBefore() default true;
}
