package com.trustledger.core.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.domain.AccountSideTotal;
import com.trustledger.core.ledger.domain.JournalLineRepository;
import com.trustledger.core.ledger.domain.LedgerAccount;
import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LedgerAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID WALLET = UUID.randomUUID();
    private static final String CODE = "WALLET:" + WALLET;

    @Mock
    LedgerAccountRepository accounts;

    @Mock
    JournalLineRepository lines;

    LedgerAccountService service;

    @BeforeEach
    void setUp() {
        service = new LedgerAccountService(accounts, lines, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LedgerAccount saved(LedgerAccount account) {
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    @Test
    void opensTheWalletAccountAsACreditNormalLiability() {
        when(accounts.findByCode(CODE)).thenReturn(Optional.empty());
        when(accounts.saveAndFlush(any())).thenAnswer(call -> saved(call.getArgument(0)));

        UUID id = service.openWalletAccount(WALLET, Money.INR);

        ArgumentCaptor<LedgerAccount> opened = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(accounts).saveAndFlush(opened.capture());
        LedgerAccount account = opened.getValue();
        assertThat(account.getId()).isEqualTo(id);
        assertThat(account.getCode()).isEqualTo(CODE);
        assertThat(account.getType()).isEqualTo(AccountType.LIABILITY);
        assertThat(account.getNormalSide()).isEqualTo(Direction.CREDIT);
        assertThat(account.getCurrency()).isEqualTo(Money.INR);
        assertThat(account.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void existingAccountIsReturnedAsItIs() {
        LedgerAccount existing = saved(LedgerAccount.open(CODE, AccountType.LIABILITY, Money.INR, NOW));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(existing));

        assertThat(service.openWalletAccount(WALLET, Money.INR)).isEqualTo(existing.getId());
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void existingAccountInAnotherCurrencyIsABug() {
        LedgerAccount existing = saved(LedgerAccount.open(CODE, AccountType.LIABILITY, Money.INR, NOW));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(existing));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.openWalletAccount(WALLET, Currency.getInstance("USD")));
    }

    @Test
    void creditNormalBalanceIsCreditsMinusDebits() {
        LedgerAccount account = saved(LedgerAccount.open(CODE, AccountType.LIABILITY, Money.INR, NOW));
        when(accounts.findByCode(CODE)).thenReturn(Optional.of(account));
        when(lines.sumAmount(account.getId(), Direction.DEBIT)).thenReturn(20_000L);
        when(lines.sumAmount(account.getId(), Direction.CREDIT)).thenReturn(150_050L);

        assertThat(service.balanceOf(CODE))
                .isEqualTo(new AccountBalance(CODE, Direction.CREDIT, 130_050, Money.INR));
    }

    @Test
    void debitNormalBalanceIsDebitsMinusCredits() {
        LedgerAccount account = saved(LedgerAccount.open("PLATFORM:X", AccountType.ASSET, Money.INR, NOW));
        when(accounts.findByCode("PLATFORM:X")).thenReturn(Optional.of(account));
        when(lines.sumAmount(account.getId(), Direction.DEBIT)).thenReturn(150_050L);
        when(lines.sumAmount(account.getId(), Direction.CREDIT)).thenReturn(50L);

        assertThat(service.balanceOf("PLATFORM:X").balanceMinor()).isEqualTo(150_000);
    }

    @Test
    void unknownAccountIsNotFound() {
        when(accounts.findByCode("WALLET:nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.balanceOf("WALLET:nope")).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void balancesOfCombinesTheGroupedTotalsOnEachNormalSide() {
        LedgerAccount wallet = saved(LedgerAccount.open(CODE, AccountType.LIABILITY, Money.INR, NOW));
        LedgerAccount clearing = saved(LedgerAccount.open("PLATFORM:GATEWAY_CLEARING", AccountType.ASSET, Money.INR,
                NOW));
        LedgerAccount untouched = saved(LedgerAccount.open("WALLET:" + UUID.randomUUID(), AccountType.LIABILITY,
                Money.INR, NOW));
        when(accounts.findAllByCodeIn(any())).thenReturn(List.of(wallet, clearing, untouched));
        when(lines.sumByAccountAndDirection(any())).thenReturn(List.of(
                new AccountSideTotal(wallet.getId(), Direction.CREDIT, 900),
                new AccountSideTotal(wallet.getId(), Direction.DEBIT, 150),
                new AccountSideTotal(clearing.getId(), Direction.DEBIT, 900)));

        Map<String, AccountBalance> balances = service.balancesOf(List.of(CODE, clearing.getCode(),
                untouched.getCode(), "WALLET:unknown"));

        assertThat(balances).containsOnlyKeys(CODE, clearing.getCode(), untouched.getCode());
        assertThat(balances.get(CODE).balanceMinor()).isEqualTo(750);
        assertThat(balances.get(clearing.getCode()).balanceMinor()).isEqualTo(900);
        assertThat(balances.get(clearing.getCode()).normalSide()).isEqualTo(Direction.DEBIT);
        assertThat(balances.get(untouched.getCode()).balanceMinor()).isZero();
    }

    @Test
    void balancesOfNothingRunsNoQuery() {
        assertThat(service.balancesOf(List.of())).isEmpty();

        verify(accounts, never()).findAllByCodeIn(any());
        verify(lines, never()).sumByAccountAndDirection(any());
    }

    @Test
    void balancesOfOnlyUnknownCodesSkipsTheLineQuery() {
        when(accounts.findAllByCodeIn(any())).thenReturn(List.of());

        assertThat(service.balancesOf(List.of("WALLET:unknown"))).isEmpty();
        verify(lines, never()).sumByAccountAndDirection(any());
    }
}
