package com.trustledger.core.shared.web;

import com.trustledger.core.shared.security.Role;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/ping}: any authenticated caller. Shows who the API thinks you are, so
 * {@code http/auth.http} can check a Keycloak token end to end. Returns only the subject (Keycloak user
 * UUID) and the mapped roles, never names or e-mail addresses.
 */
@RestController
public class PingController {

    public record Pong(String status, String subject, List<String> roles) {
    }

    @GetMapping(path = "/ping", version = "1")
    Pong ping(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(Role.AUTHORITY_PREFIX))
                .map(a -> a.substring(Role.AUTHORITY_PREFIX.length()))
                .sorted()
                .toList();
        return new Pong("pong", authentication.getName(), roles);
    }
}
