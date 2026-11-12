package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TrustLedgerExceptionTest {

    @Test
    void carriesCodeAndDetail() {
        var ex = new TrustLedgerException(ErrorCode.WALLET_FROZEN, "Wallet is frozen.");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.WALLET_FROZEN);
        assertThat(ex.detail()).isEqualTo("Wallet is frozen.");
        assertThat(ex).hasMessage("Wallet is frozen.");
    }

    @Test
    void requiresErrorCode() {
        assertThatThrownBy(() -> new TrustLedgerException(null, "x")).isInstanceOf(NullPointerException.class);
    }
}
