package com.trustledger.core.wallet.web;

import com.trustledger.core.wallet.application.ReconciliationJob;
import com.trustledger.core.wallet.application.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /admin/reconciliation/run} (SPEC §5, FR-L-4), ADMIN only: runs the reconciliation now and returns
 * its report. 409 {@code REQUEST_IN_PROGRESS} while another run holds the lock.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(AdminReconciliationController.class);

    private final Callers callers;
    private final ReconciliationJob job;

    public AdminReconciliationController(Callers callers, ReconciliationJob job) {
        this.callers = callers;
        this.job = job;
    }

    @PostMapping(path = "/admin/reconciliation/run", version = "1")
    ReconciliationReport run(@AuthenticationPrincipal Jwt jwt) {
        log.info("Manual reconciliation requested by user {}", callers.userId(jwt));
        return job.runNow();
    }
}
