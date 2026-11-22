package com.trustledger.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditActor;
import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletFrozen;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class AuditListenersTest {

    private final AuditWriter writer = mock(AuditWriter.class);
    private final AuditContextProvider provider = mock(AuditContextProvider.class);
    private final AuditListeners listeners = new AuditListeners(writer, provider);
    private final AuditContext threadContext = new AuditContext(new AuditActor(UUID.randomUUID(), "ADMIN"),
            "203.0.113.7", "thread-corr");

    private AuditContext writtenContext() {
        ArgumentCaptor<AuditContext> captor = ArgumentCaptor.forClass(AuditContext.class);
        verify(writer).write(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void theEventCorrelationIdWins() {
        when(provider.current()).thenReturn(threadContext);

        listeners.on(new WalletFrozen(UUID.randomUUID(), UUID.randomUUID(), FreezeSource.COMPLIANCE, Instant.EPOCH,
                new EventMetadata(UUID.randomUUID(), "event-corr")));

        AuditContext written = writtenContext();
        assertThat(written.correlationId()).isEqualTo("event-corr");
        assertThat(written.actor()).isEqualTo(threadContext.actor());
        assertThat(written.ip()).isEqualTo("203.0.113.7");
    }

    @Test
    void withoutEventCorrelationTheThreadOneIsKept() {
        when(provider.current()).thenReturn(threadContext);

        listeners.on(new TransferCompleted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.of(100, Money.INR), UUID.randomUUID(), Instant.EPOCH,
                new EventMetadata(UUID.randomUUID(), null)));

        assertThat(writtenContext().correlationId()).isEqualTo("thread-corr");
    }

    @Test
    void eventsWithoutMetadataUseTheThreadContext() {
        when(provider.current()).thenReturn(threadContext);

        listeners.on(new KycLevelUpgraded(UUID.randomUUID(), KycLevel.MIN));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(writer).write(entry.capture(), any());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.KYC_LEVEL_UPGRADED);
        assertThat(writtenContext()).isEqualTo(threadContext);
    }

    /** ADR 0008: in the publishing transaction, last of all, and never without a transaction. */
    @Test
    void everyHandlerRunsBeforeCommitAndLast() {
        Method[] handlers = Arrays.stream(AuditListeners.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("on")).toArray(Method[]::new);

        assertThat(handlers).hasSize(7).allSatisfy(m -> {
            TransactionalEventListener listener = m.getAnnotation(TransactionalEventListener.class);
            assertThat(listener).isNotNull();
            assertThat(listener.phase()).isEqualTo(TransactionPhase.BEFORE_COMMIT);
            assertThat(listener.fallbackExecution()).isFalse();
            assertThat(m.getAnnotation(Order.class).value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        });
    }
}
