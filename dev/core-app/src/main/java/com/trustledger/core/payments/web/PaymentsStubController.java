package com.trustledger.core.payments.web;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.web.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Placeholders so the money endpoints exist for security and rate-limit rules (M2) before payments is
 * built. Both return 501 {@code NOT_IMPLEMENTED}. Replaced by the real controllers in M6 (transfers) and
 * M7 (top-ups), which add the required {@code Idempotency-Key} header.
 */
@RestController
class PaymentsStubController {

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/transfers", version = "1")
    ResponseEntity<ProblemDetail> transfer(HttpServletRequest request) {
        return notImplemented("P2P transfers arrive in milestone M6.", request);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/topups", version = "1")
    ResponseEntity<ProblemDetail> topup(HttpServletRequest request) {
        return notImplemented("Top-ups arrive in milestone M7.", request);
    }

    /** Built directly, not thrown: an expected stub response is not an error worth a stack trace in the log. */
    private static ResponseEntity<ProblemDetail> notImplemented(String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetails.of(ErrorCode.NOT_IMPLEMENTED, detail, request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
