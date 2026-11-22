package com.trustledger.core.privacy.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.payments.PaymentsApi;
import com.trustledger.core.privacy.PersonalDataErased;
import com.trustledger.core.privacy.domain.AmlCaseProjection;
import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import com.trustledger.core.privacy.domain.DataRequestStatus;
import com.trustledger.core.privacy.domain.DataRequestType;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /me/erasure-request} (FR-PR-2): anonymises the customer's personal data and keeps the financial records,
 * all in <b>one transaction</b>, so a refusal or a failure leaves everything exactly as it was.
 * <ol>
 *   <li>Lock the wallet ({@code SELECT ... FOR UPDATE}, the money-movement lock): no transfer can move money on it
 *       until the erasure commits or rolls back.</li>
 *   <li>Refuse while an AML case on the wallet is open ({@code AML_CASE_OPEN}): an investigation needs the person's
 *       identity. Read from core-app's own copy of the AML case list, so no remote call happens under the lock.</li>
 *   <li>Refuse while money is on the wallet or on its way ({@code WALLET_NOT_EMPTY}), then close it.</li>
 *   <li>Erase the personal fields and blind indexes in identity; blank the consent IPs.</li>
 *   <li>Record a COMPLETED ERASURE request and publish {@link PersonalDataErased}, which the audit module writes as an
 *       audit event before the commit.</li>
 * </ol>
 * Untouched on purpose: the ledger, transfers, top-ups, audit events and KYC records. They are financial or
 * regulatory records with their own retention periods (ADR 0009).
 */
@Service
public class ErasureService {

    private static final Logger log = LoggerFactory.getLogger(ErasureService.class);

    private final DataRequestRepository requests;
    private final IdentityApi identity;
    private final WalletApi wallets;
    private final PaymentsApi payments;
    private final AmlCaseProjection amlCases;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ErasureService(DataRequestRepository requests, IdentityApi identity, WalletApi wallets, PaymentsApi payments,
            AmlCaseProjection amlCases, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.identity = identity;
        this.wallets = wallets;
        this.payments = payments;
        this.amlCases = amlCases;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @throws TrustLedgerException {@code AML_CASE_OPEN}, {@code WALLET_NOT_EMPTY}, {@code WALLET_FROZEN}, or
     *                              {@code INVALID_STATE_TRANSITION} if the user is already erased (also for the
     *                              loser of two racing requests)
     */
    @Transactional
    public DataRequestView requestErasure(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        if (requests.existsByUserIdAndTypeAndStatus(userId, DataRequestType.ERASURE, DataRequestStatus.COMPLETED)) {
            throw alreadyErased();
        }
        Optional<WalletView> wallet = wallets.findByUser(userId);
        UUID walletId = wallet.map(WalletView::walletId).orElse(null);
        if (walletId != null) {
            closeWallet(walletId);
        }
        try {
            identity.erasePersonalData(userId);
        } catch (OptimisticLockingFailureException raceLost) {
            // A user without a wallet has no lock to queue on: the version check catches the second request.
            throw alreadyErased();
        }
        Instant now = clock.instant();
        DataRequest request;
        try {
            request = requests.saveAndFlush(DataRequest.erasure(userId, now));
        } catch (DataIntegrityViolationException raceLost) {
            // uk_data_requests_one_erasure: another request erased this user first.
            throw alreadyErased();
        }
        events.publishEvent(new PersonalDataErased(userId, request.getId(), walletId, now));
        log.info("Personal data erased request={} wallet={}", request.getId(), walletId);
        return DataRequestView.of(request, null);
    }

    private void closeWallet(UUID walletId) {
        // The lock first: the checks below then see a wallet no transfer can change until we commit.
        WalletView locked = wallets.lockForUpdate(List.of(walletId)).getFirst();
        if (locked.status() == WalletStatus.CLOSED) {
            // Only erasure closes a wallet: a racing request erased this user while we waited for the lock.
            throw alreadyErased();
        }
        if (amlCases.hasOpenCase(walletId)) {
            throw new TrustLedgerException(ErrorCode.AML_CASE_OPEN,
                    "Your account is under a compliance review. Erasure is possible once the review is finished.");
        }
        if (!locked.balance().isZero() || !locked.held().isZero()) {
            throw new TrustLedgerException(ErrorCode.WALLET_NOT_EMPTY, "Your wallet still holds "
                    + locked.balance().toDecimalString() + " INR. Transfer or spend it, then ask again.");
        }
        if (payments.hasMoneyInFlight(walletId)) {
            throw new TrustLedgerException(ErrorCode.WALLET_NOT_EMPTY,
                    "A transfer or top-up for your wallet is still in progress. Ask again once it has finished.");
        }
        wallets.close(walletId);
    }

    private static TrustLedgerException alreadyErased() {
        return new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                "This account's personal data has already been erased.");
    }
}
