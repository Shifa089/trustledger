package com.trustledger.core.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a ProblemDetail straight to the servlet response, for errors raised in servlet filters
 * (security, rate limiting). Filters run before Spring MVC, so {@code GlobalExceptionHandler} never sees
 * those errors; this produces the same JSON shape as MVC's ProblemDetail serialization.
 */
public final class ProblemDetailWriter {

    private final JsonMapper jsonMapper;

    public ProblemDetailWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** Sets status and {@code application/problem+json}; does nothing if the response is already committed. */
    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), flatten(problem));
    }

    /** Extra properties ({@code errorCode}, {@code correlationId}, ...) at the top level, as MVC does. */
    static Map<String, Object> flatten(ProblemDetail problem) {
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
