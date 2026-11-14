package com.trustledger.core.shared.security;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints (src/test, never shipped), one per path group of {@link SecurityConfig}, so the
 * security matrix can be tested before the real controllers exist. Served under {@code /api/v1}.
 */
@RestController
class SecurityProbeController {

    private static final Map<String, String> OK = Map.of("status", "ok");

    @GetMapping(path = "/compliance/probe", version = "1")
    Map<String, String> compliance() {
        return OK;
    }

    @GetMapping(path = "/admin/probe", version = "1")
    Map<String, String> admin() {
        return OK;
    }

    @GetMapping(path = "/admin/ledger/accounts/{code}/balance", version = "1")
    Map<String, String> ledgerBalance(@PathVariable String code) {
        return OK;
    }

    @GetMapping(path = "/audit/probe", version = "1")
    Map<String, String> audit() {
        return OK;
    }

    @PostMapping(path = "/webhooks/probe", version = "1")
    Map<String, String> webhook() {
        return OK;
    }

    @GetMapping(path = "/customer/probe", version = "1")
    Map<String, String> anyAuthenticated() {
        return OK;
    }

    /** Only "authenticated" by URL; the method rule makes it ADMIN-only. Proves method security is on. */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(path = "/customer/method-secured", version = "1")
    Map<String, String> methodSecured() {
        return OK;
    }
}
