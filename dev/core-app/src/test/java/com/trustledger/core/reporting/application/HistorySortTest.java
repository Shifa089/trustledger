package com.trustledger.core.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.payments.TransactionSort;
import com.trustledger.core.payments.TransactionSort.Field;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HistorySortTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void defaultsToNewestFirst(String sort) {
        assertThat(HistorySort.parse(sort)).isEqualTo(TransactionSort.NEWEST_FIRST);
    }

    @Test
    void acceptsWhitelistedFieldsWithAndWithoutDirection() {
        assertThat(HistorySort.parse("createdAt")).isEqualTo(new TransactionSort(Field.CREATED_AT, false));
        assertThat(HistorySort.parse("createdAt,asc")).isEqualTo(new TransactionSort(Field.CREATED_AT, true));
        assertThat(HistorySort.parse("amount,desc")).isEqualTo(new TransactionSort(Field.AMOUNT, false));
        assertThat(HistorySort.parse("amount,ASC")).isEqualTo(new TransactionSort(Field.AMOUNT, true));
        assertThat(HistorySort.parse("amount")).isEqualTo(new TransactionSort(Field.AMOUNT, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "note", "walletId", "amountMinor", "created_at", "amount,sideways",
            "amount,asc,createdAt", "amount,asc,createdAt,desc", "amount;drop table x", ",asc"})
    void refusesAnythingElse(String sort) {
        assertThatThrownBy(() -> HistorySort.parse(sort)).isInstanceOf(TrustLedgerException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
