package com.trustledger.aml.shared.web;

import com.trustledger.aml.shared.ErrorCode;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** The RFC 9457 ProblemDetail of every error response, shaped like core-app's ({@code errorCode}, ...). */
public final class ProblemDetails {

    static final String CORRELATION_ID = "correlationId";

    private ProblemDetails() {
    }

    /** {@code detail} is client-visible: it must never contain personal data. */
    public static ProblemDetail of(ErrorCode code, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setInstance(URI.create(path));
        problem.setProperty("errorCode", code.name());
        String correlationId = MDC.get(CORRELATION_ID);
        if (correlationId != null) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
        return problem;
    }
}
