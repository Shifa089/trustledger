package com.trustledger.core.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.application.LedgerBalanceService.LedgerBalanceView;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LedgerAccountBalanceResponseTest {

    @ParameterizedTest
    @CsvSource({"0, 0.00", "5, 0.05", "150050, 1500.50", "-1250, -12.50", "-1, -0.01",
            "9223372036854775807, 92233720368547758.07"})
    void minorUnitsBecomeASignedTwoDecimalString(long minor, String expected) {
        assertThat(LedgerAccountBalanceResponse.decimal(minor, 2)).isEqualTo(expected);
    }

    @Test
    void mapsEveryField() {
        Instant asOf = Instant.parse("2026-11-23T10:15:30Z");
        LedgerAccountBalanceResponse response = LedgerAccountBalanceResponse.of(new LedgerBalanceView(
                new AccountBalance("PLATFORM:SUSPENSE", Direction.CREDIT, -1_250, Money.INR), asOf));

        assertThat(response).isEqualTo(new LedgerAccountBalanceResponse("PLATFORM:SUSPENSE", Direction.CREDIT,
                "-12.50", "INR", asOf));
    }
}
