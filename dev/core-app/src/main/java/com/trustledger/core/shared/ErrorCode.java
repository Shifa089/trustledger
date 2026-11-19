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
    /** The aggregate is not in a state that allows this step, e.g. approving a SUBMITTED KYC case. */
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    KYC_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "KYC already submitted"),
    PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Phone number already registered"),
    /** A FULL KYC case cannot be verified before every required document type is uploaded. */
    KYC_DOCUMENTS_MISSING(HttpStatus.CONFLICT, "KYC documents missing"),
    /** Someone else changed the same record first (optimistic lock); reload and retry. */
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    WALLET_FROZEN(HttpStatus.CONFLICT, "Wallet frozen"),
    /** The same business event (or the reversal of an entry) was already posted to the ledger. */
    DUPLICATE_POSTING(HttpStatus.CONFLICT, "Duplicate posting"),
    /** Uploaded file larger than the configured maximum (5 MB for KYC documents). */
    DOCUMENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Document too large"),
    /** Uploaded file is not a PDF, JPEG or PNG by its magic bytes (the client's Content-Type is ignored). */
    UNSUPPORTED_DOCUMENT_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported document type"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds"),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Limit exceeded"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency key reused"),
    AGE_REQUIREMENT_NOT_MET(HttpStatus.UNPROCESSABLE_CONTENT, "Age requirement not met"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable"),
    /** The gateway refused our request (4xx: bad keys, unknown ID, invalid field). Retrying will not help. */
    GATEWAY_REJECTED(HttpStatus.BAD_GATEWAY, "Payment gateway rejected the request"),
    /** Document storage (S3) could not be reached or refused the request. */
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Document storage unavailable"),
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
