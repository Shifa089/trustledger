package com.trustledger.core.privacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.privacy.PersonalDataErased;
import com.trustledger.core.privacy.domain.AmlCaseProjection;
import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import com.trustledger.core.privacy.domain.DataRequestStatus;
import com.trustledger.core.privacy.domain.DataRequestType;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/** FR-PR-2 erasure rules; the database side (one transaction, races) is {@code ErasureIT}. */
class ErasureServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID WALLET = UUID.fromString("00000000-0000-7000-8000-0000000000cc");
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final DataRequestRepository requests = mock(DataRequestRepository.class);
    private final IdentityApi identity = mock(IdentityApi.class);
    private final WalletApi wallets = mock(WalletApi.class);
    private final PaymentsApi payments = mock(PaymentsApi.class);
    private final AmlCaseProjection amlCases = mock(AmlCaseProjection.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ErasureService service = new ErasureService(requests, identity, wallets, payments, amlCases, events,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void saveLikeJpa() {
        when(requests.saveAndFlush(any())).thenAnswer(call -> {
            DataRequest request = call.getArgument(0);
            ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
            return request;
        });
    }

    @Test
    void anEmptyWalletIsLockedCheckedClosedThenThePersonIsErasedAndAudited() {
        givenWallet(WalletStatus.ACTIVE, 0, 0);

        DataRequestView view = service.requestErasure(USER);

        assertThat(view.type()).isEqualTo(DataRequestType.ERASURE);
        assertThat(view.status()).isEqualTo(DataRequestStatus.COMPLETED);
        assertThat(view.completedAt()).isEqualTo(NOW);
        InOrder order = inOrder(wallets, amlCases, payments, identity, requests, events);
        order.verify(wallets).lockForUpdate(List.of(WALLET));
        order.verify(amlCases).hasOpenCase(WALLET);
        order.verify(payments).hasMoneyInFlight(WALLET);
        order.verify(wallets).close(WALLET);
        order.verify(identity).erasePersonalData(USER);
        order.verify(requests).saveAndFlush(any());
        ArgumentCaptor<PersonalDataErased> event = ArgumentCaptor.forClass(PersonalDataErased.class);
        order.verify(events).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(USER);
        assertThat(event.getValue().walletId()).isEqualTo(WALLET);
        assertThat(event.getValue().dataRequestId()).isEqualTo(view.id());
        assertThat(event.getValue().erasedAt()).isEqualTo(NOW);
    }

    @Test
    void aUserWithoutAWalletIsErasedWithoutWalletSteps() {
        when(wallets.findByUser(USER)).thenReturn(Optional.empty());

        service.requestErasure(USER);

        verify(wallets, never()).lockForUpdate(any());
        verify(wallets, never()).close(any());
        verifyNoInteractions(amlCases, payments);
        verify(identity).erasePersonalData(USER);
        ArgumentCaptor<PersonalDataErased> event = ArgumentCaptor.forClass(PersonalDataErased.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().walletId()).isNull();
    }

    @Test
    void anOpenAmlCaseIsRefusedBeforeAnythingChanges() {
        givenWallet(WalletStatus.ACTIVE, 0, 0);
        when(amlCases.hasOpenCase(WALLET)).thenReturn(true);

        assertRefused(ErrorCode.AML_CASE_OPEN);
    }

    @Test
    void aBalanceIsRefusedWithTheAmount() {
        givenWallet(WalletStatus.ACTIVE, 15_025, 0);

        assertThatThrownBy(() -> service.requestErasure(USER)).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.WALLET_NOT_EMPTY);
                    assertThat(e.getMessage()).contains("150.25");
                });
        assertNothingChanged();
    }

    @Test
    void moneyOnItsWayIsRefused() {
        givenWallet(WalletStatus.ACTIVE, 0, 0);
        when(payments.hasMoneyInFlight(WALLET)).thenReturn(true);

        assertRefused(ErrorCode.WALLET_NOT_EMPTY);
    }

    @Test
    void anAlreadyErasedUserIsRefusedWithoutTouchingTheWallet() {
        when(requests.existsByUserIdAndTypeAndStatus(USER, DataRequestType.ERASURE, DataRequestStatus.COMPLETED))
                .thenReturn(true);

        assertRefused(ErrorCode.INVALID_STATE_TRANSITION);
        verifyNoInteractions(wallets);
    }

    @Test
    void aWalletClosedWhileWeWaitedForTheLockMeansARacingErasureWon() {
        givenWallet(WalletStatus.CLOSED, 0, 0);

        assertRefused(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void aLostRaceOnTheUserOrTheUniqueIndexIsReportedAsAlreadyErased() {
        when(wallets.findByUser(USER)).thenReturn(Optional.empty());
        doThrow(new OptimisticLockingFailureException("version")).when(identity).erasePersonalData(USER);

        assertThatThrownBy(() -> service.requestErasure(USER)).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        ErasureService other = new ErasureService(requests, mock(IdentityApi.class), wallets, payments, amlCases,
                events, Clock.fixed(NOW, ZoneOffset.UTC));
        doThrow(new DataIntegrityViolationException("uk_data_requests_one_erasure")).when(requests).saveAndFlush(any());
        assertThatThrownBy(() -> other.requestErasure(USER)).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verify(events, never()).publishEvent(any(Object.class));
    }

    private void givenWallet(WalletStatus status, long balance, long held) {
        WalletView view = new WalletView(WALLET, USER, WalletTier.MIN, status, Money.of(balance, Money.INR),
                Money.of(held, Money.INR), Money.of(balance - held, Money.INR));
        when(wallets.findByUser(USER)).thenReturn(Optional.of(view));
        when(wallets.lockForUpdate(List.of(WALLET))).thenReturn(List.of(view));
    }

    private void assertRefused(ErrorCode code) {
        assertThatThrownBy(() -> service.requestErasure(USER)).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
        assertNothingChanged();
    }

    private void assertNothingChanged() {
        verify(wallets, never()).close(any());
        verify(identity, never()).erasePersonalData(any());
        verify(requests, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
