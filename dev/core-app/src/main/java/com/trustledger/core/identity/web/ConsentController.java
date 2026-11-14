package com.trustledger.core.identity.web;

import com.trustledger.core.identity.application.ConsentOverview;
import com.trustledger.core.identity.application.ConsentService;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.identity.domain.ConsentChoice;
import com.trustledger.core.identity.domain.ConsentPurpose;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET/POST /api/v1/me/consents} (FR-ID-3, CUSTOMER only). Always the caller's own consents: the
 * user comes from the token, never from the request, so there is no ID to tamper with (no BOLA).
 */
@RestController
public class ConsentController {

    /** {@code {"consents":[{"purpose":"TERMS","policyVersion":"2026-11-01","granted":true}, ...]}} */
    public record ConsentRequest(
            @NotEmpty @Size(max = 3) List<@Valid @NotNull Decision> consents) {
    }

    public record Decision(
            @NotNull ConsentPurpose purpose,
            @NotBlank @Size(max = 32) String policyVersion,
            @NotNull Boolean granted) {
    }

    private final UserProvisioningService provisioning;
    private final ConsentService consents;

    public ConsentController(UserProvisioningService provisioning, ConsentService consents) {
        this.provisioning = provisioning;
        this.consents = consents;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping(path = "/me/consents", version = "1")
    ConsentOverview current(@AuthenticationPrincipal Jwt jwt) {
        return consents.current(userId(jwt));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/me/consents", version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    ConsentOverview record(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConsentRequest request,
            HttpServletRequest http) {
        List<ConsentChoice> choices = request.consents().stream()
                .map(d -> new ConsentChoice(d.purpose(), d.policyVersion(), d.granted()))
                .toList();
        // Direct peer address. Behind a load balancer this needs trusted X-Forwarded-For handling (M14).
        return consents.record(userId(jwt), choices, http.getRemoteAddr());
    }

    private UUID userId(Jwt jwt) {
        return provisioning.ensureUser(TokenClaims.identity(jwt));
    }
}
