package com.trustledger.core.wallet.web;

import com.trustledger.core.wallet.application.MyWalletView;
import com.trustledger.core.wallet.application.WalletQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own wallet (SPEC §5). There is no wallet ID in the path: the wallet is found from the token,
 * so one customer can never ask for another's (BOLA).
 */
@RestController
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletController {

    private final Callers callers;
    private final WalletQueryService queries;

    public WalletController(Callers callers, WalletQueryService queries) {
        this.callers = callers;
        this.queries = queries;
    }

    @GetMapping(path = "/wallets/me", version = "1")
    MyWalletView me(@AuthenticationPrincipal Jwt jwt) {
        return queries.mine(callers.userId(jwt));
    }
}
