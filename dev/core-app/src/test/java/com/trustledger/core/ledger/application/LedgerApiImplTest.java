package com.trustledger.core.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerApiImplTest {

    private final LedgerPostingService posting = mock(LedgerPostingService.class);
    private final LedgerReversalService reversals = mock(LedgerReversalService.class);
    private final LedgerAccountService accounts = mock(LedgerAccountService.class);
    private final LedgerApiImpl api = new LedgerApiImpl(posting, reversals, accounts);

    private static final Money TEN = Money.of(1_000, Money.INR);
    private static final List<PostingLine> LINES = List.of(PostingLine.debit(AccountCodes.GATEWAY_CLEARING, TEN),
            PostingLine.credit(AccountCodes.SUSPENSE, TEN));

    @Test
    void postDelegates() {
        PostingCommand command = PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), LINES);
        UUID id = UUID.randomUUID();
        when(posting.post(command)).thenReturn(id);

        assertThat(api.post(command)).isEqualTo(id);
    }

    /** Only reverse() may post a REVERSAL: it builds the lines from the original, so they can't be made up. */
    @Test
    void postRefusesAHandMadeReversal() {
        PostingCommand reversal = PostingCommand.reversal(UUID.randomUUID(), "why", LINES);

        assertThatThrownBy(() -> api.post(reversal)).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(posting, never()).post(any());
    }

    @Test
    void postRequiresACommand() {
        assertThatNullPointerException().isThrownBy(() -> api.post(null));
    }

    @Test
    void otherMethodsDelegate() {
        UUID entry = UUID.randomUUID();
        UUID reversal = UUID.randomUUID();
        UUID wallet = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        AccountBalance balance = new AccountBalance("WALLET:x", Direction.CREDIT, 5, Money.INR);
        when(reversals.reverse(entry, "why")).thenReturn(reversal);
        when(accounts.balanceOf("WALLET:x")).thenReturn(balance);
        when(accounts.openWalletAccount(wallet, Money.INR)).thenReturn(account);

        assertThat(api.reverse(entry, "why")).isEqualTo(reversal);
        assertThat(api.balanceOf("WALLET:x")).isEqualTo(balance);
        assertThat(api.openWalletAccount(wallet, Money.INR)).isEqualTo(account);
    }
}
