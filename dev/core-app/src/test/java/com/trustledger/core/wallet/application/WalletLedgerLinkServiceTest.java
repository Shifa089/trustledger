package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.shared.Money;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WalletLedgerLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Mock
    WalletRepository wallets;

    @Mock
    LedgerApi ledger;

    WalletLedgerLinkService service;

    private final UUID walletId = UUID.randomUUID();
    private final Wallet wallet = Wallet.open(UUID.randomUUID(), WalletTier.MIN, NOW.minusSeconds(60));

    @BeforeEach
    void setUp() {
        service = new WalletLedgerLinkService(wallets, ledger, Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(wallet, "id", walletId);
    }

    @Test
    void opensTheAccountAndStoresItsIdUnderTheLock() {
        UUID account = UUID.randomUUID();
        when(wallets.lockById(walletId)).thenReturn(Optional.of(wallet));
        when(ledger.openWalletAccount(walletId, Money.INR)).thenReturn(account);

        assertThat(service.link(walletId)).isEqualTo(account);

        assertThat(wallet.getLedgerAccountId()).isEqualTo(account);
        assertThat(wallet.getUpdatedAt()).isEqualTo(NOW);
        verify(wallets).saveAndFlush(wallet);
    }

    @Test
    void alreadyLinkedWalletIsLeftAlone() {
        UUID account = UUID.randomUUID();
        wallet.linkLedgerAccount(account, NOW);
        when(wallets.lockById(walletId)).thenReturn(Optional.of(wallet));

        assertThat(service.link(walletId)).isEqualTo(account);

        verifyNoInteractions(ledger);
        verify(wallets, never()).saveAndFlush(any());
    }

    @Test
    void missingWalletIsABug() {
        when(wallets.lockById(walletId)).thenReturn(Optional.empty());

        assertThatIllegalStateException().isThrownBy(() -> service.link(walletId));
        verifyNoInteractions(ledger);
    }
}
