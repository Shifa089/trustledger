package com.trustledger.core.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletTier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletFreezeTest {

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    private final Wallet wallet = Wallet.open(UUID.randomUUID(), WalletTier.FULL, T0);

    @Test
    void startsOpen() {
        WalletFreeze freeze = WalletFreeze.start(wallet, FreezeSource.AML, "R4 fan-out", null, T0);

        assertThat(freeze.isOpen()).isTrue();
        assertThat(freeze.getCreatedAt()).isEqualTo(T0);
        assertThat(freeze.getUnfrozenAt()).isNull();
        assertThat(freeze.walletId()).isEqualTo(wallet.getId());
    }

    @Test
    void endsOnlyOnce() {
        WalletFreeze freeze = WalletFreeze.start(wallet, FreezeSource.AML, "R4 fan-out", null, T0);
        freeze.end(UUID.randomUUID(), "cleared", T0);

        assertThatIllegalStateException().isThrownBy(() -> freeze.end(UUID.randomUUID(), "again", T0));
    }

    @Test
    void cannotEndBeforeItStarted() {
        WalletFreeze freeze = WalletFreeze.start(wallet, FreezeSource.AML, "R4 fan-out", null, T0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> freeze.end(UUID.randomUUID(), "cleared", T0.minusSeconds(1)));
        assertThat(freeze.isOpen()).isTrue();
    }

    @Test
    void needsWalletSourceReasonAndTime() {
        assertThatNullPointerException().isThrownBy(() -> WalletFreeze.start(null, FreezeSource.AML, "r", null, T0));
        assertThatNullPointerException().isThrownBy(() -> WalletFreeze.start(wallet, null, "r", null, T0));
        assertThatNullPointerException()
                .isThrownBy(() -> WalletFreeze.start(wallet, FreezeSource.AML, null, null, T0));
        assertThatNullPointerException()
                .isThrownBy(() -> WalletFreeze.start(wallet, FreezeSource.AML, "r", null, null));
    }
}
