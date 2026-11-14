package com.trustledger.core.shared.security;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.web.ProblemDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
    private final JsonMapper jsonMapper;

    ProblemDetailSecurityHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, ex);
        // Never echo the exception message: it can describe the token.
        write(response, ProblemDetails.of(ErrorCode.UNAUTHENTICATED,
                "A valid bearer token is required.", request.getRequestURI()));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        bearerAccessDenied.handle(request, response, ex);
        write(response, ProblemDetails.of(ErrorCode.ACCESS_DENIED,
                "You are not allowed to perform this action.", request.getRequestURI()));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), flatten(problem));
    }

    /** Same JSON shape as MVC's ProblemDetail serialization: extra properties at the top level. */
    private static Map<String, Object> flatten(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", problem.getInstance() == null ? null : problem.getInstance().toString());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }
}
