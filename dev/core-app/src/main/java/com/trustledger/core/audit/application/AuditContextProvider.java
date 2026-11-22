package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.AuditContext;

/**
 * Port: who is acting on the current thread and from where (security context, HTTP request, MDC). Implemented in
 * infrastructure, so the application layer stays free of servlet and Spring Security types.
 */
public interface AuditContextProvider {

    /** Never {@code null}; {@link AuditContext#SYSTEM} when nobody is authenticated and no request is running. */
    AuditContext current();
}
