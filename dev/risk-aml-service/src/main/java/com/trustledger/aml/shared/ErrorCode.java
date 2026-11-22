package com.trustledger.aml.shared;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error codes of the AML API (SPEC §5). A copy of core-app's approach (ADR 0007: the two
 * services share contracts only): each code maps to one HTTP status and a ProblemDetail type URI
 * {@code https://trustledger.dev/errors/<kebab-case-name>}.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    /** The case is not in a state that allows this step (FR-AML-4), e.g. closing an OPEN case. */
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    /** Only the officer the case is assigned to may close or escalate it. */
    NOT_CASE_ASSIGNEE(HttpStatus.CONFLICT, "Not the case assignee"),
    /** Someone else changed the same case first (optimistic lock); reload and retry. */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    /** Framework-level 405/406/415; the handler keeps the framework's actual status. */
    UNSUPPORTED_REQUEST(HttpStatus.BAD_REQUEST, "Unsupported request"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private static final String TYPE_BASE = "https://trustledger.dev/errors/";

    private final HttpStatus status;
    private final String title;
    private final URI type;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
        this.type = URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return type;
    }
}
