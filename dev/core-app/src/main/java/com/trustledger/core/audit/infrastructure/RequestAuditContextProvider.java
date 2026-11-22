package com.trustledger.core.audit.infrastructure;

import com.trustledger.core.audit.application.AuditContextProvider;
import com.trustledger.core.audit.domain.AuditActor;
import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reads the audit context from the committing thread:
 * <ul>
 *   <li>actor: the Keycloak subject of the access token, as our user ID (the same kind of ID as {@code frozen_by},
 *       {@code approved_by}), and its most privileged role. No token: SYSTEM. The AML freeze consumer, scheduled
 *       jobs and listeners that run after commit on another thread have none.</li>
 *   <li>IP: the servlet request's remote address. With a proxy in front, Boot's {@code forward-headers-strategy}
 *       decides whether that is the client.</li>
 *   <li>user agent: the request's {@code User-Agent} header, as sent (normalised by {@code AuditEvent}).</li>
 *   <li>correlation ID: the MDC value set by {@link CorrelationIdFilter} (or by the Kafka consumer).</li>
 * </ul>
 */
@Component
class RequestAuditContextProvider implements AuditContextProvider {

    private final IdentityApi identity;

    RequestAuditContextProvider(IdentityApi identity) {
        this.identity = identity;
    }

    @Override
    public AuditContext current() {
        HttpServletRequest request = request();
        return new AuditContext(actor(), request == null ? null : request.getRemoteAddr(),
                MDC.get(CorrelationIdFilter.MDC_KEY), request == null ? null : request.getHeader(HttpHeaders.USER_AGENT));
    }

    private AuditActor actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            return AuditActor.SYSTEM;
        }
        UUID userId = identity.findUserId(token.getToken().getSubject()).orElse(null);
        List<String> authorities = token.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        return AuditActor.of(userId, authorities);
    }

    private static HttpServletRequest request() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
