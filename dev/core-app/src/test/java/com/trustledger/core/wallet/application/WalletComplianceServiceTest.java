package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletUnfrozen;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WalletComplianceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID OFFICER = UUID.randomUUID();

    @Mock
    WalletRepository wallets;

    @Mock
    ApplicationEventPublisher events;

    WalletComplianceService service;
    Wallet wallet;

    @BeforeEach
    void setUp() {
        service = new WalletComplianceService(wallets, events, Clock.fixed(NOW, ZoneOffset.UTC));
        wallet = Wallet.open(UUID.randomUUID(), WalletTier.FULL, NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(wallet, "id", UUID.randomUUID());
    }

    @Test
    void freezeLocksFreezesAndPublishes() {
        when(wallets.lockById(wallet.getId())).thenReturn(Optional.of(wallet));

        ComplianceWalletView view = service.freeze(wallet.getId(), OFFICER, "Fraud report");

        assertThat(view.status()).isEqualTo(WalletStatus.FROZEN);
        assertThat(view.currentFreeze().reason()).isEqualTo("Fraud report");
        assertThat(view.currentFreeze().frozenBy()).isEqualTo(OFFICER);
        assertThat(view.currentFreeze().frozenAt()).isEqualTo(NOW);
        assertThat(view.currentFreeze().toString()).doesNotContain("Fraud report");
        verify(wallets).saveAndFlush(wallet);
        verify(events).publishEvent(new WalletFrozen(wallet.getId(), wallet.getUserId(), FreezeSource.COMPLIANCE));
    }

    @Test
    void refusedFreezePublishesNothing() {
        when(wallets.lockById(wallet.getId())).thenReturn(Optional.of(wallet));
        service.freeze(wallet.getId(), OFFICER, "first");

        assertThatThrownBy(() -> service.freeze(wallet.getId(), OFFICER, "second"))
                .isInstanceOf(TrustLedgerException.class);

        verify(events).publishEvent(any(WalletFrozen.class)); // only the first
    }

    @Test
    void unfreezeLocksUnfreezesAndPublishes() {
        when(wallets.lockById(wallet.getId())).thenReturn(Optional.of(wallet));
        service.freeze(wallet.getId(), OFFICER, "check");

        ComplianceWalletView view = service.unfreeze(wallet.getId(), OFFICER, "cleared");

        assertThat(view.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(view.currentFreeze()).isNull();
        verify(events).publishEvent(new WalletUnfrozen(wallet.getId(), wallet.getUserId()));
    }

    @Test
    void unknownWalletIsNotFound() {
        UUID id = UUID.randomUUID();
        when(wallets.lockById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.freeze(id, OFFICER, "x"))
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(wallets, never()).saveAndFlush(any());
    }
}
