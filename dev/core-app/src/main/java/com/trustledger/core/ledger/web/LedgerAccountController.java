package com.trustledger.core.ledger.web;

import com.trustledger.core.ledger.application.LedgerBalanceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /admin/ledger/accounts/{code}/balance} (SPEC §5, FR-L-2): the ledger-derived balance of any account, for
 * ADMIN and AUDITOR (SecurityConfig, repeated here). Read-only; 404 for an unknown account, 400 for a malformed code.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
public class LedgerAccountController {

    private final LedgerBalanceService balances;

    public LedgerAccountController(LedgerBalanceService balances) {
        this.balances = balances;
    }

    @GetMapping(path = "/admin/ledger/accounts/{code}/balance", version = "1")
    public LedgerAccountBalanceResponse balance(@PathVariable String code) {
        return LedgerAccountBalanceResponse.of(balances.balanceOf(code));
    }
}
