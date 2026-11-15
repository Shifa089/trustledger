package com.trustledger.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.ledger.LedgerSql.Line;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wallet accounts and ledger-derived balances through {@link LedgerApi}. Postings are written with raw SQL, so
 * these tests don't depend on {@code LedgerPostingService.post()}.
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

    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> ledger.balanceOf(AccountCodes.wallet(UUID.randomUUID())))
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
