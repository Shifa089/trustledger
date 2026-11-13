package com.trustledger.core.shared.web;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to an RFC 9457 ProblemDetail with {@code errorCode} and {@code correlationId}
 * (SPEC §5). Never returns stack traces, exception messages of unexpected errors, or rejected values.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String GENERIC_DETAIL = "An unexpected error occurred. Quote the correlationId when contacting support.";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One validation problem. Deliberately has no rejected value: it may be personal data. */
    public record Violation(String field, String message) {
    }

    @ExceptionHandler(TrustLedgerException.class)
    ResponseEntity<ProblemDetail> handleTrustLedger(TrustLedgerException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.warn("Request failed with {}", code, ex);
        } else {
            log.info("Request rejected with {}", code);
        }
        return respond(problem(code, ex.detail(), request.getRequestURI()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        List<Violation> violations = ex.getConstraintViolations().stream()
                .map(v -> new Violation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return respond(validationProblem(violations, request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(problem(ErrorCode.UNAUTHENTICATED, "Authentication is required.", request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(problem(ErrorCode.ACCESS_DENIED, "You are not allowed to perform this action.",
                request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error", ex);
        return respond(problem(ErrorCode.INTERNAL_ERROR, GENERIC_DETAIL, request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> violations.add(new Violation(e.getField(), e.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> violations.add(new Violation(e.getObjectName(), e.getDefaultMessage())));
        return ResponseEntity.badRequest().body(validationProblem(violations, path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors errors) {
                for (FieldError e : errors.getFieldErrors()) {
                    violations.add(new Violation(e.getField(), e.getDefaultMessage()));
                }
            } else {
                String param = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable e : result.getResolvableErrors()) {
                    violations.add(new Violation(param, e.getDefaultMessage()));
                }
            }
        });
        return ResponseEntity.badRequest().body(validationProblem(violations, path(request)));
    }

    /** Enriches every framework-generated error (400 bad JSON, 404, 405, 415, ...) with our fields. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = codeFor(statusCode);
        String detail = statusCode.is5xxServerError() ? GENERIC_DETAIL
                : body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail() : code.title();
        ProblemDetail problem = problem(code, detail, path(request));
        problem.setStatus(statusCode.value()); // keep the framework's status, e.g. 405 or 415
        return ResponseEntity.status(statusCode).headers(headers).body(problem);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.NOT_FOUND;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.UNSUPPORTED_REQUEST;
        };
    }

    private static ProblemDetail validationProblem(List<Violation> violations, String path) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "The request contains invalid fields.", path);
        problem.setProperty("violations", violations);
        return problem;
    }

    private static ProblemDetail problem(ErrorCode code, String detail, String path) {
        return ProblemDetails.of(code, detail, path);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "/";
    }
}
