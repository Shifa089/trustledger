package com.trustledger.core.shared.web;

import com.trustledger.core.shared.ErrorCode;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 9457 ProblemDetail used for every error response (SPEC §5): {@code type, title,
 * status, detail, instance, errorCode, correlationId}. Shared by {@link GlobalExceptionHandler} and the
 * Spring Security entry point / access-denied handler, which run before MVC and so never reach the advice.
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    /** {@code detail} is client-visible: it must never contain personal data. */
    public static ProblemDetail of(ErrorCode code, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(path));
        problem.setProperty("errorCode", code.name());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
