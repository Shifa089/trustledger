package com.trustledger.core.wallet.web;

import com.trustledger.core.shared.audit.AuditDetails;
import com.trustledger.core.shared.audit.Audited;
import com.trustledger.core.shared.masking.Masking;
import com.trustledger.core.wallet.application.ComplianceWalletView;
import com.trustledger.core.wallet.application.WalletComplianceService;
import com.trustledger.core.wallet.domain.Wallet;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Freeze and unfreeze (SPEC §5, FR-W-3), COMPLIANCE_OFFICER only. The officer comes from the token. */
@RestController
@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")
public class ComplianceWalletController {

    /** {@code {"reason":"Suspicious activity reported by the bank"}}. {@code toString()} is masked. */
    public record ReasonRequest(@NotBlank @Size(max = Wallet.MAX_REASON_LENGTH) String reason)
            implements AuditDetails {

        /** The reason may quote personal data: the audit trail gets its length only. */
        @Override
        public Map<String, Object> auditDetails() {
            return Map.of("reasonLength", reason == null ? 0 : reason.length());
        }

        @Override
        public String toString() {
            return "ReasonRequest[reason=" + Masking.FULLY_MASKED + "]";
        }
    }

    private final Callers callers;
    private final WalletComplianceService compliance;

    public ComplianceWalletController(Callers callers, WalletComplianceService compliance) {
        this.callers = callers;
        this.compliance = compliance;
    }

    @Audited(action = "WALLET_FREEZE", entityType = "WALLET")
    @PostMapping(path = "/compliance/wallets/{id}/freeze", version = "1")
    public ComplianceWalletView freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request) {
        return compliance.freeze(id, callers.userId(jwt), request.reason());
    }

    @Audited(action = "WALLET_UNFREEZE", entityType = "WALLET")
    @PostMapping(path = "/compliance/wallets/{id}/unfreeze", version = "1")
    public ComplianceWalletView unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request) {
        return compliance.unfreeze(id, callers.userId(jwt), request.reason());
    }
}
