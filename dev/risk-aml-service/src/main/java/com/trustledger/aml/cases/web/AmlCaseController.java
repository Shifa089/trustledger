package com.trustledger.aml.cases.web;

import com.trustledger.aml.cases.application.AmlCaseService;
import com.trustledger.aml.cases.application.CaseViews.CaseDetail;
import com.trustledger.aml.cases.application.CaseViews.CasePage;
import com.trustledger.aml.cases.application.CaseViews.StrReport;
import com.trustledger.aml.cases.domain.CaseStatus;
import com.trustledger.aml.cases.web.CaseRequests.AssignCaseRequest;
import com.trustledger.aml.cases.web.CaseRequests.CloseCaseRequest;
import com.trustledger.aml.cases.web.CaseRequests.EscalateCaseRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AML case workflow (FR-AML-4, SPEC §5 "AML service"): {@code /api/v1/aml/cases/...}, COMPLIANCE_OFFICER only
 * (SecurityConfig). Illegal steps are 409 ProblemDetails from the domain.
 */
@RestController
@RequestMapping("/aml/cases")
class AmlCaseController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final AmlCaseService service;

    AmlCaseController(AmlCaseService service) {
        this.service = service;
    }

    @GetMapping(version = "1")
    CasePage list(@RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, walletId, page, size);
    }

    @GetMapping(path = "/{id}", version = "1")
    CaseDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping(path = "/{id}/assign", version = "1")
    CaseDetail assign(@PathVariable UUID id, @RequestBody(required = false) AssignCaseRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.assign(id, Callers.officerId(jwt), body == null ? null : body.assigneeId());
    }

    @PostMapping(path = "/{id}/close", version = "1")
    CaseDetail close(@PathVariable UUID id, @Valid @RequestBody CloseCaseRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.closeAsFalsePositive(id, Callers.officerId(jwt), body.notes());
    }

    @PostMapping(path = "/{id}/escalate", version = "1")
    CaseDetail escalate(@PathVariable UUID id, @Valid @RequestBody EscalateCaseRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.escalate(id, Callers.officerId(jwt), body.notes());
    }

    /** The STR export as a download; never cached (it is evidence about a customer). */
    @GetMapping(path = "/{id}/str-report", version = "1")
    ResponseEntity<String> strReport(@PathVariable UUID id) {
        StrReport report = service.strReport(id);
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(report.fileName()).build().toString())
                .body(report.csv());
    }
}
