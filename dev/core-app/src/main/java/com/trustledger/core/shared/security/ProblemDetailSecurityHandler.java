package com.trustledger.core.shared.security;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.web.ProblemDetailWriter;
import com.trustledger.core.shared.web.ProblemDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 raised inside the Spring Security filter chain. They happen before Spring MVC, so the
 * {@code GlobalExceptionHandler} never sees them; this writes the same ProblemDetail body
 * ({@code errorCode}, {@code correlationId}). The bearer-token delegates add the RFC 6750
 * {@code WWW-Authenticate: Bearer ...} header first.
 */
class ProblemDetailSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final ProblemDetailWriter writer;

    ProblemDetailSecurityHandler(JsonMapper jsonMapper) {
        this.writer = new ProblemDetailWriter(jsonMapper);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, ex);
        // Never echo the exception message: it can describe the token.
        writer.write(response, ProblemDetails.of(ErrorCode.UNAUTHENTICATED,
                "A valid bearer token is required.", request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        bearerAccessDenied.handle(request, response, ex);
        writer.write(response, ProblemDetails.of(ErrorCode.ACCESS_DENIED,
                "You are not allowed to perform this action.", request.getRequestURI()));
    }
}
