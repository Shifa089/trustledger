package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import com.trustledger.core.wallet.application.LimitsProperties.TierLimits;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletLocks;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
class WalletApiImplTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Mock
    WalletRepository wallets;

    @Mock
    WalletLocks locks;

    WalletApiImpl api;

    @BeforeEach
    void setUp() {
        LimitsProperties limits = new LimitsProperties(Money.parse("50000.00"),
                new TierLimits(Money.parse("10000.00"), Money.parse("10000.00"), false, null),
                new TierLimits(Money.parse("200000.00"), Money.parse("200000.00"), true, Money.parse("100000.00")));
        api = new WalletApiImpl(wallets, locks, limits, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Wallet wallet(WalletTier tier, long balanceMinor) {
        Wallet wallet = Wallet.open(UUID.randomUUID(), tier, NOW);
        ReflectionTestUtils.setField(wallet, "id", UUID.randomUUID());
        if (balanceMinor > 0) {
            wallet.credit(Money.of(balanceMinor, Money.INR), NOW);
        }
        return wallet;
    }

    @Test
    void lockForUpdateRemovesDuplicatesAndReturnsTheDatabaseOrder() {
        Wallet a = wallet(WalletTier.FULL, 0);
        Wallet b = wallet(WalletTier.FULL, 0);
        when(wallets.lockAllOrderedById(any())).thenReturn(List.of(b, a));

        List<WalletView> locked = api.lockForUpdate(List.of(a.getId(), b.getId(), a.getId()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(wallets).lockAllOrderedById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(locked).extracting(WalletView::walletId).containsExactly(b.getId(), a.getId());
    }

    @Test
    void lockForUpdateWithAnUnknownIdIsNotFound() {
        Wallet a = wallet(WalletTier.FULL, 0);
        when(wallets.lockAllOrderedById(any())).thenReturn(List.of(a));

        assertThatThrownBy(() -> api.lockForUpdate(List.of(a.getId(), UUID.randomUUID())))
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void lockForUpdateNeedsIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> api.lockForUpdate(List.of()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> api.lockForUpdate(new ArrayList<>(Arrays.asList(UUID.randomUUID(), null))));
        verify(wallets, never()).lockAllOrderedById(any());
    }

    @Test
    void debitOnALockedWalletChangesItAndReturnsTheNewState() {
        Wallet wallet = wallet(WalletTier.FULL, 10_000);
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(locks.isLockedForUpdate(wallet)).thenReturn(true);

        WalletView view = api.debit(wallet.getId(), Money.of(2_500, Money.INR));

        assertThat(view.balance().amountMinor()).isEqualTo(7_500);
        assertThat(wallet.balance().amountMinor()).isEqualTo(7_500);
    }

    @Test
    void creditHoldAndReleaseGoThroughTheAggregate() {
        Wallet wallet = wallet(WalletTier.FULL, 0);
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(locks.isLockedForUpdate(wallet)).thenReturn(true);

        api.credit(wallet.getId(), Money.of(10_000, Money.INR));
        api.hold(wallet.getId(), Money.of(4_000, Money.INR));
        WalletView view = api.releaseHold(wallet.getId(), Money.of(1_000, Money.INR));

        assertThat(view.balance().amountMinor()).isEqualTo(10_000);
        assertThat(view.held().amountMinor()).isEqualTo(3_000);
        assertThat(view.available().amountMinor()).isEqualTo(7_000);
    }

    @Test
    void movingMoneyOnAnUnlockedWalletIsABug() {
        Wallet wallet = wallet(WalletTier.FULL, 10_000);
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(locks.isLockedForUpdate(wallet)).thenReturn(false);

        assertThatIllegalStateException().isThrownBy(() -> api.debit(wallet.getId(), Money.of(1, Money.INR)))
                .withMessageContaining("lockForUpdate");

        assertThat(wallet.balance().amountMinor()).isEqualTo(10_000);
    }

    @Test
    void unknownWalletIsNotFound() {
        UUID id = UUID.randomUUID();
        when(wallets.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> api.credit(id, Money.of(1, Money.INR)))
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> api.limitsFor(id)).isInstanceOf(TrustLedgerException.class);
    }

    @Test
    void limitsFollowTheTier() {
        Wallet min = wallet(WalletTier.MIN, 0);
        Wallet full = wallet(WalletTier.FULL, 0);
        when(wallets.findById(min.getId())).thenReturn(Optional.of(min));
        when(wallets.findById(full.getId())).thenReturn(Optional.of(full));

        assertThat(api.limitsFor(min.getId()).p2pAllowed()).isFalse();
        assertThat(api.limitsFor(full.getId()).dailyTransfer()).isEqualTo(Money.parse("100000.00"));
    }

    @Test
    void findByUserMapsToAView() {
        Wallet wallet = wallet(WalletTier.MIN, 500);
        when(wallets.findByUserId(wallet.getUserId())).thenReturn(Optional.of(wallet));

        assertThat(api.findByUser(wallet.getUserId())).get()
                .satisfies(view -> {
                    assertThat(view.walletId()).isEqualTo(wallet.getId());
                    assertThat(view.isActive()).isTrue();
                    assertThat(view.available().amountMinor()).isEqualTo(500);
                });
    }
}
