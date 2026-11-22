package com.trustledger.core.audit.web;

import com.trustledger.core.audit.application.AuditEventPage;
import com.trustledger.core.audit.application.AuditQueryService;
import com.trustledger.core.audit.application.AuditVerificationService;
import com.trustledger.core.audit.domain.AuditEventFilter;
import com.trustledger.core.audit.domain.ChainVerification;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail for auditors (SPEC §5, FR-A-1, FR-A-2), AUDITOR only and read-only. {@code SecurityConfig}
 * already restricts {@code /api/*}{@code /audit/**} to auditors; {@code @PreAuthorize} repeats it here.
 */
@RestController
@PreAuthorize("hasRole('AUDITOR')")
public class AuditController {

    /**
     * The verification result. {@code brokenSeq} and {@code reason} only when BROKEN; {@code lastHash} is the
     * value to compare with a copy kept outside the database.
     */
    public record VerifyResponse(String status, long checkedEvents, Long lastSeq, String lastHash, Long brokenSeq,
            String reason) {

        static VerifyResponse of(ChainVerification result) {
            return new VerifyResponse(result.status().name(), result.checkedEvents(), result.lastSeq(),
                    result.lastHash(), result.brokenSeq(), result.reason() == null ? null : result.reason().name());
        }
    }

    private final AuditQueryService query;
    private final AuditVerificationService verification;

    public AuditController(AuditQueryService query, AuditVerificationService verification) {
        this.query = query;
        this.verification = verification;
    }

    /**
     * Newest first. {@code from} is included and {@code to} is not (ISO-8601 instants, for example
     * {@code 2026-11-22T00:00:00Z}).
     */
    @GetMapping(path = "/audit/events", version = "1")
    public AuditEventPage events(@RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) Integer size) { // bounds: trustledger.paging, checked in the service
        return query.search(new AuditEventFilter(entityType, entityId, actorId, action, from, to), page, size);
    }

    /** Always walks the live chain: never cache the answer. */
    @GetMapping(path = "/audit/verify", version = "1")
    public ResponseEntity<VerifyResponse> verify() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(VerifyResponse.of(verification.verify()));
    }
}
