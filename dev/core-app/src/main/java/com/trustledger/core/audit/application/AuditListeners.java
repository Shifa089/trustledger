package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.wallet.WalletCreated;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletUnfrozen;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * FR-A-1: every public domain event becomes an audit event, written <b>in the publishing transaction</b>, just
 * before it commits ({@link TransactionPhase#BEFORE_COMMIT}). Deliberately not an {@code @ApplicationModuleListener}
 * (ADR 0008):
 * <ul>
 *   <li>atomic: a business change never commits without its audit row, and a rolled-back one leaves none;</li>
 *   <li>the caller's identity, IP and correlation ID are still on this thread;</li>
 *   <li>no redelivery, so no de-duplication key. Spring Modulith records publications for AFTER_COMMIT listeners
 *       only, so these never appear in {@code event_publication}.</li>
 * </ul>
 * {@link Ordered#LOWEST_PRECEDENCE}: after any other BEFORE_COMMIT work, so the chain's advisory lock is the last
 * lock taken and is held as briefly as possible. An event published outside a transaction is not audited
 * ({@code fallbackExecution = false}); every publisher today is a {@code @Transactional} application service.
 */
@Component
class AuditListeners {

    private final AuditWriter writer;
    private final AuditContextProvider context;

    AuditListeners(AuditWriter writer, AuditContextProvider context) {
        this.writer = writer;
        this.context = context;
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void on(KycLevelUpgraded event) {
        write(AuditEntries.of(event), null);
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void on(WalletCreated event) {
        write(AuditEntries.of(event), null);
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void on(WalletFrozen event) {
        write(AuditEntries.of(event), event.metadata());
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void on(WalletUnfrozen event) {
        write(AuditEntries.of(event), null);
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void on(TopupCaptured event) {
        write(AuditEntries.of(event), event.metadata());
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void on(TransferCompleted event) {
        write(AuditEntries.of(event), event.metadata());
    }

    /** The event's own correlation ID wins: it was captured when the event was published. */
    private void write(AuditEntry entry, EventMetadata metadata) {
        AuditContext current = context.current();
        writer.write(entry, metadata == null ? current : current.withCorrelationId(metadata.correlationId()));
    }
}
