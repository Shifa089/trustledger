package com.trustledger.core.reporting.web;

import com.trustledger.core.payments.LimitUsage;
import com.trustledger.core.reporting.application.LimitUsageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC §5 "limits used": {@code GET /wallets/me/limit-usage} (CUSTOMER). No wallet ID in the path, so one customer
 * can never read another's (BOLA). {@code SecurityConfig} already limits {@code /api/*}{@code /wallets/**} to
 * customers; {@code @PreAuthorize} repeats it here.
 */
@RestController
@PreAuthorize("hasRole('CUSTOMER')")
public class LimitUsageController {

    /**
     * Amounts as decimal strings (SPEC §6). Transfers count when COMPLETED or HELD_FOR_REVIEW; money added counts
     * open top-ups (CREATED, PENDING) as well as CAPTURED ones. Day and month start in Asia/Kolkata.
     */
    public record LimitUsageResponse(String transferredToday, String transferredThisMonth, String addedThisMonth) {

        static LimitUsageResponse of(LimitUsage usage) {
            return new LimitUsageResponse(usage.transferredToday().toDecimalString(),
                    usage.transferredThisMonth().toDecimalString(), usage.addedThisMonth().toDecimalString());
        }
    }

    private final Callers callers;
    private final LimitUsageService usage;

    public LimitUsageController(Callers callers, LimitUsageService usage) {
        this.callers = callers;
        this.usage = usage;
    }

    @GetMapping(path = "/wallets/me/limit-usage", version = "1")
    LimitUsageResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return LimitUsageResponse.of(usage.mine(callers.userId(jwt)));
    }
}
