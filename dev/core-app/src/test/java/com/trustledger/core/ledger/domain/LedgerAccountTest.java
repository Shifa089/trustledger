package com.trustledger.core.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LedgerAccountTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void walletAccountIsACreditNormalLiability() {
        LedgerAccount account = LedgerAccount.open("WALLET:a", AccountType.LIABILITY, Money.INR, NOW);

        assertThat(account.getCode()).isEqualTo("WALLET:a");
        assertThat(account.getType()).isEqualTo(AccountType.LIABILITY);
        assertThat(account.getNormalSide()).isEqualTo(Direction.CREDIT);
        assertThat(account.getCurrency()).isEqualTo(Money.INR);
        assertThat(account.getCreatedAt()).isEqualTo(NOW);
        assertThat(account.getId()).isNull();
    }

    @Test
    void assetIsDebitNormal() {
        assertThat(LedgerAccount.open("PLATFORM:X", AccountType.ASSET, Money.INR, NOW).getNormalSide())
                .isEqualTo(Direction.DEBIT);
    }

    @Test
    void creditNormalBalanceIsCreditsMinusDebits() {
        LedgerAccount wallet = LedgerAccount.open("WALLET:a", AccountType.LIABILITY, Money.INR, NOW);

        assertThat(wallet.balanceFrom(300, 1_000)).isEqualTo(700);
        assertThat(wallet.balanceFrom(1_000, 300)).isEqualTo(-700);
    }

    @Test
    void debitNormalBalanceIsDebitsMinusCredits() {
        LedgerAccount clearing = LedgerAccount.open("PLATFORM:X", AccountType.ASSET, Money.INR, NOW);

        assertThat(clearing.balanceFrom(1_000, 300)).isEqualTo(700);
    }

    @Test
    void balanceNeverWrapsAround() {
        LedgerAccount wallet = LedgerAccount.open("WALLET:a", AccountType.LIABILITY, Money.INR, NOW);

        assertThatThrownBy(() -> wallet.balanceFrom(-1, Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void requiredFields() {
        assertThatNullPointerException().isThrownBy(() -> LedgerAccount.open(null, AccountType.ASSET, Money.INR, NOW));
        assertThatNullPointerException().isThrownBy(() -> LedgerAccount.open("A:B", null, Money.INR, NOW));
        assertThatNullPointerException().isThrownBy(() -> LedgerAccount.open("A:B", AccountType.ASSET, null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> LedgerAccount.open("A:B", AccountType.ASSET, Money.INR, null));
    }
}
