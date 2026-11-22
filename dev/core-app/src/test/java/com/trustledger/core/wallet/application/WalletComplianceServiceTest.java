package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.AmlFreezeOutcome;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletUnfrozen;
import com.trustledger.core.wallet.domain.ProcessedEventRepository;
import com.trustledger.core.wallet.domain.Wallet;
import com.trustledger.core.wallet.domain.WalletRepository;
import com.trustledger.events.AccountFreezeRequestedV1;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WalletComplianceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    // Placeholder: comparisons ignore metadata (its eventId is random); tests assert it is present separately.
    private static final EventMetadata ANY_METADATA = new EventMetadata(UUID.randomUUID(), null);
    private static final UUID OFFICER = UUID.randomUUID();

    @Mock
    WalletRepository wallets;

    @Mock
    ApplicationEventPublisher events;

    @Mock
    ProcessedEventRepository processedEvents;

    WalletComplianceService service;
    Wallet wallet;

    @BeforeEach
    void setUp() {
        service = new WalletComplianceService(wallets, processedEvents, events, Clock.fixed(NOW, ZoneOffset.UTC));
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
        ArgumentCaptor<WalletFrozen> published = ArgumentCaptor.forClass(WalletFrozen.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).usingRecursiveComparison().ignoringFields("metadata")
                .isEqualTo(new WalletFrozen(wallet.getId(), wallet.getUserId(), FreezeSource.COMPLIANCE, NOW,
                        ANY_METADATA));
        assertThat(published.getValue().metadata().eventId()).isNotNull();
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

    // --- FR-AML-3: freezes requested by the AML service ------------------------------------------------------------

    @Test
    void anAmlRequestFreezesAnActiveWalletWithSourceAmlAndNoOfficer() {
        UUID eventId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(processedEvents.markProcessed(eventId, AccountFreezeRequestedV1.TOPIC, NOW)).thenReturn(true);
        when(wallets.lockById(wallet.getId())).thenReturn(Optional.of(wallet));

        AmlFreezeOutcome outcome = service.freezeForAml(eventId, wallet.getId(), caseId, "R2");

        assertThat(outcome).isEqualTo(AmlFreezeOutcome.FROZEN);
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.FROZEN);
        ComplianceWalletView view = ComplianceWalletView.of(wallet);
        assertThat(view.currentFreeze().source()).isEqualTo(FreezeSource.AML);
        assertThat(view.currentFreeze().frozenBy()).isNull();
        assertThat(view.currentFreeze().reason()).isEqualTo("AML case " + caseId + ", rule R2");
        verify(wallets).saveAndFlush(wallet);
        ArgumentCaptor<WalletFrozen> published = ArgumentCaptor.forClass(WalletFrozen.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue().source()).isEqualTo(FreezeSource.AML);
        assertThat(published.getValue().walletId()).isEqualTo(wallet.getId());
    }

    @Test
    void aDuplicateAmlRequestChangesNothing() {
        UUID eventId = UUID.randomUUID();
        when(processedEvents.markProcessed(eventId, AccountFreezeRequestedV1.TOPIC, NOW)).thenReturn(false);

        assertThat(service.freezeForAml(eventId, wallet.getId(), UUID.randomUUID(), "R2"))
                .isEqualTo(AmlFreezeOutcome.DUPLICATE);

        verify(wallets, never()).lockById(any());
        verify(events, never()).publishEvent(any());
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
    }

    @Test
    void anAmlRequestLeavesAnExistingFreezeAsItIs() {
        when(wallets.lockById(wallet.getId())).thenReturn(Optional.of(wallet));
        service.freeze(wallet.getId(), OFFICER, "Fraud report");
        when(processedEvents.markProcessed(any(), any(), any())).thenReturn(true);

        assertThat(service.freezeForAml(UUID.randomUUID(), wallet.getId(), UUID.randomUUID(), "R4"))
                .isEqualTo(AmlFreezeOutcome.ALREADY_FROZEN);

        assertThat(ComplianceWalletView.of(wallet).currentFreeze().source()).isEqualTo(FreezeSource.COMPLIANCE);
        verify(events).publishEvent(any(WalletFrozen.class)); // only the officer's
    }

    @Test
    void anAmlRequestForAClosedWalletChangesNothing() {
        ReflectionTestUtils.setField(wallet, "status", WalletStatus.CLOSED);
        when(wallets.lockById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(processedEvents.markProcessed(any(), any(), any())).thenReturn(true);

        assertThat(service.freezeForAml(UUID.randomUUID(), wallet.getId(), UUID.randomUUID(), "R2"))
                .isEqualTo(AmlFreezeOutcome.WALLET_CLOSED);

        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.CLOSED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void anAmlRequestForAnUnknownWalletIsNotFoundSoItsClaimRollsBack() {
        UUID id = UUID.randomUUID();
        when(processedEvents.markProcessed(any(), any(), any())).thenReturn(true);
        when(wallets.lockById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.freezeForAml(UUID.randomUUID(), id, UUID.randomUUID(), "R2"))
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void aMalformedRuleCodeIsRefusedBeforeAnythingIsClaimed() {
        for (String bad : new String[] {null, "", "R", "X2", "R2; DROP", "R123"}) {
            assertThatThrownBy(() -> service.freezeForAml(UUID.randomUUID(), wallet.getId(), UUID.randomUUID(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(processedEvents, never()).markProcessed(any(), any(), any());
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
