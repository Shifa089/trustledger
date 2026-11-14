package com.trustledger.core.shared;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Stable, client-facing error codes (SPEC §5). Each maps to one HTTP status and a ProblemDetail
 * type URI {@code https://trustledger.dev/errors/<kebab-case-name>}.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    KYC_REQUIRED(HttpStatus.FORBIDDEN, "KYC required"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "Request in progress"),
    MAKER_CHECKER_VIOLATION(HttpStatus.CONFLICT, "Maker-checker violation"),
    WALLET_FROZEN(HttpStatus.CONFLICT, "Wallet frozen"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds"),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Limit exceeded"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable"),
    /** Endpoint declared but not built yet (stubs such as POST /transfers until M6). */
    NOT_IMPLEMENTED(HttpStatus.NOT_IMPLEMENTED, "Not implemented"),
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
