package com.trustledger.aml.cases.web;

import com.trustledger.aml.cases.application.AmlAlertService;
import com.trustledger.aml.cases.application.CaseViews.AlertPage;
import com.trustledger.aml.rules.domain.Severity;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The alert list (FR-AML-2, SPEC §5 "AML service" {@code GET /api/v1/aml/alerts}): COMPLIANCE_OFFICER only
 * (SecurityConfig, repeated here by {@code @PreAuthorize}). Filters: {@code ruleCode} (R1-R4), {@code severity}
 * (MEDIUM, HIGH), {@code walletId}; newest first.
 */
@RestController
@RequestMapping("/aml/alerts")
@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
class AmlAlertController {

    private final AmlAlertService service;

    AmlAlertController(AmlAlertService service) {
        this.service = service;
    }

    @GetMapping(version = "1")
    AlertPage list(@RequestParam(required = false) String ruleCode,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(ruleCode, severity, walletId, page, size);
    }
}
