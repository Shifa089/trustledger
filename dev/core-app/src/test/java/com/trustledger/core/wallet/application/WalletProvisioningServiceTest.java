package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.wallet.WalletCreated;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WalletProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    @Mock
    WalletRepository wallets;

    @Mock
    ApplicationEventPublisher events;

    WalletProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new WalletProvisioningService(wallets, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Simulates the database assigning the ID on insert. */
    private void insertAssignsId(UUID id) {
        when(wallets.saveAndFlush(any())).thenAnswer(call -> {
            Wallet wallet = call.getArgument(0);
            ReflectionTestUtils.setField(wallet, "id", id);
            return wallet;
        });
    }

    @Test
    void minWithoutWalletOpensAMinWalletAndPublishesWalletCreated() {
        UUID walletId = UUID.randomUUID();
        when(wallets.lockByUserId(USER)).thenReturn(Optional.empty());
        insertAssignsId(walletId);

        service.apply(USER, KycLevel.MIN);

        ArgumentCaptor<Wallet> saved = ArgumentCaptor.forClass(Wallet.class);
        verify(wallets).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTier()).isEqualTo(WalletTier.MIN);
        assertThat(saved.getValue().getUserId()).isEqualTo(USER);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        verify(events).publishEvent(new WalletCreated(walletId, USER));
    }

    @Test
    void fullWithoutWalletOpensAFullWallet() {
        when(wallets.lockByUserId(USER)).thenReturn(Optional.empty());
        insertAssignsId(UUID.randomUUID());

        service.apply(USER, KycLevel.FULL);

        ArgumentCaptor<Wallet> saved = ArgumentCaptor.forClass(Wallet.class);
        verify(wallets).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTier()).isEqualTo(WalletTier.FULL);
        verify(events).publishEvent(any(WalletCreated.class));
    }

    @Test
    void secondMinEventChangesNothing() {
        Wallet existing = Wallet.open(USER, WalletTier.MIN, NOW.minusSeconds(60));
        when(wallets.lockByUserId(USER)).thenReturn(Optional.of(existing));

        service.apply(USER, KycLevel.MIN);

        verify(wallets, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void fullEventUpgradesAMinWalletWithoutAnotherWalletCreated() {
        Wallet existing = Wallet.open(USER, WalletTier.MIN, NOW.minusSeconds(60));
        when(wallets.lockByUserId(USER)).thenReturn(Optional.of(existing));

        service.apply(USER, KycLevel.FULL);

        assertThat(existing.getTier()).isEqualTo(WalletTier.FULL);
        assertThat(existing.getUpdatedAt()).isEqualTo(NOW);
        verify(wallets).saveAndFlush(existing);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void lateMinEventNeverDowngrades() {
        Wallet existing = Wallet.open(USER, WalletTier.FULL, NOW.minusSeconds(60));
        when(wallets.lockByUserId(USER)).thenReturn(Optional.of(existing));

        service.apply(USER, KycLevel.MIN);

        assertThat(existing.getTier()).isEqualTo(WalletTier.FULL);
        verify(wallets, never()).saveAndFlush(any());
    }

    @Test
    void lostInsertRacePublishesNothing() {
        when(wallets.lockByUserId(USER)).thenReturn(Optional.empty());
        when(wallets.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_wallets_user"));

        assertThatThrownBy(() -> service.apply(USER, KycLevel.MIN))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(events, never()).publishEvent(any());
    }
}
