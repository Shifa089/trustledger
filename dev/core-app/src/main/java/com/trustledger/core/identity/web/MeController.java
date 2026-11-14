package com.trustledger.core.identity.web;

import com.trustledger.core.identity.application.UserProfile;
import com.trustledger.core.identity.application.UserProfileService;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.identity.domain.UserStatus;
import com.trustledger.core.shared.security.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/me}: the caller's own profile, masked (SPEC §5). Any role. */
@RestController
public class MeController {

    /** Every personal field is masked, e.g. {@code c***@example.test}, {@code ******3210}, {@code T*** C***}. */
    public record MeResponse(UUID id, String email, String phone, String fullName, UserStatus status,
            List<String> roles) {
    }

    private final UserProvisioningService provisioning;
    private final UserProfileService profiles;

    public MeController(UserProvisioningService provisioning, UserProfileService profiles) {
        this.provisioning = provisioning;
        this.profiles = profiles;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping(path = "/me", version = "1")
    MeResponse me(@AuthenticationPrincipal Jwt jwt, JwtAuthenticationToken authentication) {
        // Normally the JIT filter already did this; calling it here keeps /me correct on its own.
        UUID userId = provisioning.ensureUser(TokenClaims.identity(jwt));
        UserProfile profile = profiles.profile(userId);
        return new MeResponse(profile.id(), profile.email(), profile.phone(), profile.fullName(), profile.status(),
                roles(authentication));
    }

    private static List<String> roles(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(Role.AUTHORITY_PREFIX))
                .map(a -> a.substring(Role.AUTHORITY_PREFIX.length()))
                .sorted()
                .toList();
    }
}
