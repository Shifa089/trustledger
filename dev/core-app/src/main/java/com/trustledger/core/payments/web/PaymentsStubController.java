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
 * Placeholder so the top-up endpoint exists for security and rate-limit rules (M2) before M7 builds it. Returns
 * 501 {@code NOT_IMPLEMENTED}. Transfers have their real controller since M6 ({@link TransferController}).
 */
@RestController
class PaymentsStubController {

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
