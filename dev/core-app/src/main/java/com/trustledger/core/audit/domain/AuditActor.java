package com.trustledger.core.audit.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Who performed an action.
 *
 * @param actorId our user ID of the caller; {@code null} for the system (AML service, scheduled jobs, listeners
 *                that run after commit on another thread)
 * @param role    one of {@link #ROLES_BY_PRIORITY} or {@link #SYSTEM_ROLE}
 */
public record AuditActor(UUID actorId, String role) {

    public static final String SYSTEM_ROLE = "SYSTEM";
    public static final AuditActor SYSTEM = new AuditActor(null, SYSTEM_ROLE);

    /**
     * The audit stores one role per event. A token with several roles is recorded with its most privileged one, so
     * an admin who is also a customer shows up as ADMIN.
     */
    public static final List<String> ROLES_BY_PRIORITY = List.of("ADMIN", "COMPLIANCE_OFFICER", "AUDITOR", "CUSTOMER");

    private static final String AUTHORITY_PREFIX = "ROLE_";

    public AuditActor {
        if (!SYSTEM_ROLE.equals(role) && !ROLES_BY_PRIORITY.contains(role)) {
            throw new IllegalArgumentException("Unknown audit role");
        }
        if (actorId == null && !SYSTEM_ROLE.equals(role)) {
            throw new IllegalArgumentException("Only the system may act without an actor ID");
        }
    }

    /**
     * An authenticated caller. Authorities are Spring Security's ({@code ROLE_ADMIN}); anything else is ignored. A
     * caller with none of the application roles is recorded as SYSTEM, keeping the actor ID.
     */
    public static AuditActor of(UUID actorId, Collection<String> authorities) {
        for (String role : ROLES_BY_PRIORITY) {
            if (authorities.contains(AUTHORITY_PREFIX + role)) {
                return actorId == null ? SYSTEM : new AuditActor(actorId, role);
            }
        }
        return new AuditActor(actorId, SYSTEM_ROLE);
    }
}
