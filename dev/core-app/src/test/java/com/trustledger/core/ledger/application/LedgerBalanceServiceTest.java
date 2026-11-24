package com.trustledger.core.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.application.LedgerBalanceService.LedgerBalanceView;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerBalanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:15:30Z");

    private final LedgerAccountService accounts = mock(LedgerAccountService.class);
    private final LedgerBalanceService service = new LedgerBalanceService(accounts, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void returnsTheLedgerBalanceAndTheMomentItWasRead() {
        AccountBalance balance = new AccountBalance("PLATFORM:SUSPENSE", Direction.CREDIT, -1_250, Money.INR);
        when(accounts.balanceOf("PLATFORM:SUSPENSE")).thenReturn(balance);

        LedgerBalanceView view = service.balanceOf("PLATFORM:SUSPENSE");

        assertThat(view.balance()).isEqualTo(balance);
        assertThat(view.asOf()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLATFORM:GATEWAY_CLEARING", "WALLET:0193a1b2-0000-7000-8000-000000000001", "A:b-1_C"})
    void wellFormedCodesAreLookedUp(String code) {
        when(accounts.balanceOf(code)).thenReturn(new AccountBalance(code, Direction.DEBIT, 0, Money.INR));

        assertThat(service.balanceOf(code).balance().accountCode()).isEqualTo(code);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "CASH", "platform:suspense", "PLATFORM:", ":SUSPENSE", "PLATFORM:SUS PENSE",
            "PLATFORM:SUSPENSE;", "PLATFORM:../x"})
    void malformedCodesAreRefusedBeforeAnyQuery(String code) {
        assertThatThrownBy(() -> service.balanceOf(code))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(accounts);
    }

    @Test
    void codesLongerThanTheColumnAreRefused() {
        String sixtyFive = "WALLET:" + "a".repeat(58);
        assertThat(sixtyFive).hasSize(65);

        assertThatThrownBy(() -> service.balanceOf(sixtyFive)).isInstanceOf(TrustLedgerException.class);
        verifyNoInteractions(accounts);
    }
}
