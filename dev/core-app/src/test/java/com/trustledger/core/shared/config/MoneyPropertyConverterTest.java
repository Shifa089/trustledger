package com.trustledger.core.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyPropertyConverterTest {

    private final MoneyPropertyConverter converter = new MoneyPropertyConverter();

    @Test
    void convertsRupeesToPaise() {
        assertThat(converter.convert("10000.00")).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(converter.convert(" 50000 ")).isEqualTo(Money.of(5_000_000, Money.INR));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.005", "-1.00", "1e5", "1,000.00", "abc"})
    void rejectsInsteadOfRounding(String value) {
        assertThatThrownBy(() -> converter.convert(value)).isInstanceOf(TrustLedgerException.class);
    }
}
