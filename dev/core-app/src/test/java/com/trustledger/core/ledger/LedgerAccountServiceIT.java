package com.trustledger.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.LedgerSql.Line;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wallet accounts and ledger-derived balances through {@link LedgerApi}. The first tests post with raw SQL, so
 * they check {@code balanceOf} on its own; the later ones post through {@code post()}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerAccountServiceIT {

    @Autowired
    LedgerApi ledger;

    @Autowired
    LedgerSql sql;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void walletAccountIsACreditNormalLiabilityOpenedOnce() {
        UUID walletId = UUID.randomUUID();

        UUID first = ledger.openWalletAccount(walletId, Money.INR);
        UUID second = ledger.openWalletAccount(walletId, Money.INR);

        assertThat(second).isEqualTo(first);
        Map<String, Object> row = jdbc.queryForMap(
                "select code, type, normal_side, currency from ledger.ledger_accounts where id = ?", first);
        assertThat(row).containsEntry("code", "WALLET:" + walletId).containsEntry("type", "LIABILITY")
                .containsEntry("normal_side", "CREDIT").containsEntry("currency", "INR");
    }

    @Test
    void newAccountHasAZeroBalance() {
        UUID walletId = UUID.randomUUID();
        ledger.openWalletAccount(walletId, Money.INR);

        assertThat(ledger.balanceOf(AccountCodes.wallet(walletId)))
                .isEqualTo(new AccountBalance(AccountCodes.wallet(walletId), Direction.CREDIT, 0, Money.INR));
    }

    @Test
    void balanceIsDerivedFromTheJournalOnEachAccountsNormalSide() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        UUID senderAccount = ledger.openWalletAccount(sender, Money.INR);
        UUID receiverAccount = ledger.openWalletAccount(receiver, Money.INR);
        UUID clearing = sql.accountId(AccountCodes.GATEWAY_CLEARING);
        long clearingBefore = ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor();

        sql.post(Line.debit(clearing, 100_000), Line.credit(senderAccount, 100_000)); // top-up ₹1,000
        sql.post(Line.debit(senderAccount, 40_050), Line.credit(receiverAccount, 40_050)); // transfer ₹400.50

        assertThat(ledger.balanceOf(AccountCodes.wallet(sender)).balanceMinor()).isEqualTo(59_950);
        assertThat(ledger.balanceOf(AccountCodes.wallet(receiver)).toMoney()).isEqualTo(Money.of(40_050, Money.INR));
        AccountBalance clearingAfter = ledger.balanceOf(AccountCodes.GATEWAY_CLEARING);
        assertThat(clearingAfter.normalSide()).isEqualTo(Direction.DEBIT);
        assertThat(clearingAfter.balanceMinor() - clearingBefore).isEqualTo(100_000);
    }

    // --- Through post(), not raw SQL ---

    @Test
    void postedTopupGrowsTheAssetOnTheDebitSideAndTheLiabilityOnTheCreditSide() {
        UUID walletId = UUID.randomUUID();
        ledger.openWalletAccount(walletId, Money.INR);
        String wallet = AccountCodes.wallet(walletId);
        long clearingBefore = ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor();

        ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                PostingLine.debit(AccountCodes.GATEWAY_CLEARING, Money.of(75_000, Money.INR)),
                PostingLine.credit(wallet, Money.of(75_000, Money.INR)))));

        assertThat(ledger.balanceOf(wallet)).isEqualTo(new AccountBalance(wallet, Direction.CREDIT, 75_000, Money.INR));
        assertThat(ledger.balanceOf(AccountCodes.GATEWAY_CLEARING).balanceMinor())
                .isEqualTo(clearingBefore + 75_000);
    }

    /**
     * The ledger itself doesn't stop a wallet account going below zero (platform accounts may), so the balance is
     * signed; turning it into {@link Money} fails loudly, because for a wallet that means a broken ledger.
     */
    @Test
    void walletAccountBelowZeroIsSignedAndRefusedAsMoney() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ledger.openWalletAccount(a, Money.INR);
        ledger.openWalletAccount(b, Money.INR);

        ledger.post(PostingCommand.of(EntryType.P2P_TRANSFER, "TRANSFER", UUID.randomUUID(), List.of(
                PostingLine.debit(AccountCodes.wallet(a), Money.of(500, Money.INR)),
                PostingLine.credit(AccountCodes.wallet(b), Money.of(500, Money.INR)))));

        AccountBalance overdrawn = ledger.balanceOf(AccountCodes.wallet(a));
        assertThat(overdrawn.balanceMinor()).isEqualTo(-500);
        assertThatThrownBy(overdrawn::toMoney).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void balancesOfReturnsEveryKnownAccountAndSkipsUnknownCodes() {
        UUID funded = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        ledger.openWalletAccount(funded, Money.INR);
        ledger.openWalletAccount(empty, Money.INR);
        ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                PostingLine.debit(AccountCodes.GATEWAY_CLEARING, Money.of(1_250, Money.INR)),
                PostingLine.credit(AccountCodes.wallet(funded), Money.of(1_250, Money.INR)))));
        String unknown = AccountCodes.wallet(UUID.randomUUID());

        Map<String, AccountBalance> balances = ledger.balancesOf(List.of(AccountCodes.wallet(funded),
                AccountCodes.wallet(empty), AccountCodes.GATEWAY_CLEARING, unknown));

        assertThat(balances).containsOnlyKeys(AccountCodes.wallet(funded), AccountCodes.wallet(empty),
                AccountCodes.GATEWAY_CLEARING);
        assertThat(balances.get(AccountCodes.wallet(funded)).balanceMinor()).isEqualTo(1_250);
        assertThat(balances.get(AccountCodes.wallet(empty)).balanceMinor()).isZero();
        assertThat(balances.get(AccountCodes.GATEWAY_CLEARING))
                .isEqualTo(ledger.balanceOf(AccountCodes.GATEWAY_CLEARING));
    }

    @Test
    void balancesOfNothingIsEmpty() {
        assertThat(ledger.balancesOf(List.of())).isEmpty();
        assertThat(ledger.balancesOf(List.of(AccountCodes.wallet(UUID.randomUUID())))).isEmpty();
    }

    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> ledger.balanceOf(AccountCodes.wallet(UUID.randomUUID())))
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
