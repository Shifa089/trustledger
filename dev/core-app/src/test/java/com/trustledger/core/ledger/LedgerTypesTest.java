package com.trustledger.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The small public types: directions, account types, codes, lines and balances. */
class LedgerTypesTest {

    @Test
    void directionOpposite() {
        assertThat(Direction.DEBIT.opposite()).isEqualTo(Direction.CREDIT);
        assertThat(Direction.CREDIT.opposite()).isEqualTo(Direction.DEBIT);
    }

    @Test
    void assetsGrowWithDebitsEverythingElseWithCredits() {
        assertThat(AccountType.ASSET.normalSide()).isEqualTo(Direction.DEBIT);
        assertThat(AccountType.LIABILITY.normalSide()).isEqualTo(Direction.CREDIT);
        assertThat(AccountType.REVENUE.normalSide()).isEqualTo(Direction.CREDIT);
    }

    @Test
    void accountCodes() {
        UUID walletId = UUID.fromString("0193f2a0-0000-7000-8000-000000000042");

        assertThat(AccountCodes.wallet(walletId)).isEqualTo("WALLET:0193f2a0-0000-7000-8000-000000000042");
        assertThat(AccountCodes.GATEWAY_CLEARING).isEqualTo("PLATFORM:GATEWAY_CLEARING");
        assertThat(AccountCodes.SUSPENSE).isEqualTo("PLATFORM:SUSPENSE");
        assertThatNullPointerException().isThrownBy(() -> AccountCodes.wallet(null));
    }

    @Test
    void postingLineFactories() {
        Money amount = Money.of(500, Money.INR);

        assertThat(PostingLine.debit("A:B", amount)).isEqualTo(new PostingLine("A:B", Direction.DEBIT, amount));
        assertThat(PostingLine.credit("A:B", amount)).isEqualTo(new PostingLine("A:B", Direction.CREDIT, amount));
        assertThatNullPointerException().isThrownBy(() -> PostingLine.debit(null, amount));
        assertThatNullPointerException().isThrownBy(() -> new PostingLine("A:B", null, amount));
        assertThatNullPointerException().isThrownBy(() -> PostingLine.debit("A:B", null));
    }

    /** "Negative amount rejected": a negative line can't even be built, because Money refuses it. */
    @Test
    void negativeLineCannotBeExpressed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingLine.debit("A:B", Money.of(-1, Money.INR)));
    }

    @Test
    void balanceAsMoney() {
        AccountBalance balance = new AccountBalance("WALLET:x", Direction.CREDIT, 150_050, Money.INR);

        assertThat(balance.toMoney()).isEqualTo(Money.of(150_050, Money.INR));
    }

    @Test
    void negativeBalanceIsNotMoney() {
        AccountBalance balance = new AccountBalance("PLATFORM:SUSPENSE", Direction.CREDIT, -1, Money.INR);

        assertThatIllegalStateException().isThrownBy(balance::toMoney);
    }
}
