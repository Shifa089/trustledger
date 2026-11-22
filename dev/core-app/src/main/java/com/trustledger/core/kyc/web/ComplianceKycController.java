package com.trustledger.core.kyc.web;

import com.trustledger.core.kyc.application.DocumentLinkView;
import com.trustledger.core.kyc.application.KycQueuePage;
import com.trustledger.core.kyc.application.KycReviewCaseView;
import com.trustledger.core.kyc.application.KycReviewService;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.audit.AuditDetails;
import com.trustledger.core.shared.audit.Audited;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance review of KYC cases (SPEC §5, COMPLIANCE_OFFICER only; FR-KYC-3). Maker and checker are taken
 * from the token, so an officer cannot claim to be someone else.
 */
@RestController
@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
public class ComplianceKycController {

    /** {@code {"reason":"Address proof is unreadable"}}. */
    public record RejectRequest(@NotBlank @Size(max = KycCase.MAX_REASON_LENGTH) String reason)
            implements AuditDetails {

        /** The reason may quote personal data: the audit trail gets its length only. */
        @Override
        public Map<String, Object> auditDetails() {
            return Map.of("reasonLength", reason == null ? 0 : reason.length());
        }
    }

    private final Callers callers;
    private final KycReviewService review;

    public ComplianceKycController(Callers callers, KycReviewService review) {
        this.callers = callers;
        this.review = review;
    }

    @GetMapping(path = "/compliance/kyc-cases", version = "1")
    KycQueuePage queue(@RequestParam(defaultValue = "SUBMITTED") KycStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) Integer size) { // bounds: trustledger.paging, checked in the service
        return review.queue(status, page, size);
    }

    @GetMapping(path = "/compliance/kyc-cases/{id}", version = "1")
    KycReviewCaseView detail(@PathVariable UUID id) {
        return review.detail(id);
    }

    @Audited(action = "KYC_CASE_VERIFY", entityType = "KYC_CASE")
    @PostMapping(path = "/compliance/kyc-cases/{id}/verify", version = "1")
    public KycReviewCaseView verify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return review.verify(id, callers.userId(jwt));
    }

    @Audited(action = "KYC_CASE_APPROVE", entityType = "KYC_CASE")
    @PostMapping(path = "/compliance/kyc-cases/{id}/approve", version = "1")
    public KycReviewCaseView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return review.approve(id, callers.userId(jwt));
    }

    @Audited(action = "KYC_CASE_REJECT", entityType = "KYC_CASE")
    @PostMapping(path = "/compliance/kyc-cases/{id}/reject", version = "1")
    public KycReviewCaseView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody RejectRequest request) {
        return review.reject(id, callers.userId(jwt), request.reason());
    }

    /** The link is a bearer credential for 5 minutes: never cache the response. */
    @Audited(action = "KYC_DOCUMENT_VIEW", entityType = "KYC_DOCUMENT", entityIdParam = "docId",
            captureBefore = false)
    @GetMapping(path = "/compliance/kyc-cases/{id}/documents/{docId}", version = "1")
    public ResponseEntity<DocumentLinkView> document(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID docId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(review.documentLink(id, docId, callers.userId(jwt)));
    }
}
