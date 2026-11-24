package com.trustledger.core.ledger.web;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.application.LedgerBalanceService.LedgerBalanceView;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code GET /admin/ledger/accounts/{code}/balance}. {@code balance} is a decimal string on the account's normal side
 * ({@code normalSide}), and <b>signed</b>: a platform account such as suspense can legitimately be below zero.
 */
public record LedgerAccountBalanceResponse(String accountCode, Direction normalSide, String balance, String currency,
        Instant asOf) {

    static LedgerAccountBalanceResponse of(LedgerBalanceView view) {
        AccountBalance balance = view.balance();
        return new LedgerAccountBalanceResponse(balance.accountCode(), balance.normalSide(),
                decimal(balance.balanceMinor(), balance.currency().getDefaultFractionDigits()),
                balance.currency().getCurrencyCode(), view.asOf());
    }

    /** Minor units as a plain decimal string, e.g. {@code -1250 -> "-12.50"}; no floating point. */
    static String decimal(long amountMinor, int fractionDigits) {
        return BigDecimal.valueOf(amountMinor, fractionDigits).toPlainString();
    }
}
