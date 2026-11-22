package com.trustledger.core.payments.application;

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

import com.trustledger.core.payments.domain.GatewayOrder;
import com.trustledger.core.payments.domain.GatewayRejectedException;
import com.trustledger.core.payments.domain.GatewayUnavailableException;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TopupServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();
    private static final UUID TOPUP = UUID.randomUUID();
    private static final Money AMOUNT = Money.parse("500.00");
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String ORDER = "order_UNIT00000001";
    private static final String KEY_ID = "rzp_test_UNITKEY000000";

    private final TopupWriter writer = mock(TopupWriter.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final TopupService service = new TopupService(writer, gateway, new PaymentsMetrics(new SimpleMeterRegistry()));

    private static Topup created() {
        Topup topup = Topup.create(WALLET, AMOUNT, NOW);
        ReflectionTestUtils.setField(topup, "id", TOPUP);
        return topup;
    }

    private static Topup pending() {
        Topup topup = created();
        topup.markPending(ORDER, NOW);
        return topup;
    }

    private static Topup failed() {
        Topup topup = created();
        topup.fail(NOW);
        return topup;
    }

    @BeforeEach
    void setUp() {
        when(writer.open(USER, AMOUNT)).thenReturn(created());
        when(gateway.checkoutKeyId()).thenReturn(KEY_ID);
    }

    @Test
    void opensThenCreatesTheOrderThenMarksPending() {
        when(gateway.createOrder(AMOUNT, TOPUP.toString())).thenReturn(new GatewayOrder(ORDER, AMOUNT,
                TOPUP.toString(), "created"));
        when(writer.markPending(TOPUP, ORDER)).thenReturn(pending());

        TopupResult result = service.create(USER, AMOUNT);

        InOrder order = inOrder(writer, gateway);
        order.verify(writer).open(USER, AMOUNT);
        order.verify(gateway).createOrder(AMOUNT, TOPUP.toString()); // the receipt is our top-up ID
        order.verify(writer).markPending(TOPUP, ORDER);
        verify(writer, never()).markFailed(any());
        assertThat(result.topupId()).isEqualTo(TOPUP);
        assertThat(result.status()).isEqualTo(TopupStatus.PENDING);
        assertThat(result.gatewayOrderId()).isEqualTo(ORDER);
        assertThat(result.keyId()).isEqualTo(KEY_ID);
        assertThat(result.amount()).isEqualTo(AMOUNT);
    }

    @Test
    void aRefusalWhileOpeningNeverReachesTheGateway() {
        when(writer.open(USER, AMOUNT)).thenThrow(new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED, "over"));

        assertThatThrownBy(() -> service.create(USER, AMOUNT)).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
        verifyNoInteractions(gateway);
        verify(writer, never()).markFailed(any());
    }

    @Test
    void gatewayUnavailableMarksFailedAndRethrows() {
        GatewayUnavailableException down = new GatewayUnavailableException(new TimeoutException());
        when(gateway.createOrder(any(), any())).thenThrow(down);
        when(writer.markFailed(TOPUP)).thenReturn(failed());

        assertThatThrownBy(() -> service.create(USER, AMOUNT)).isSameAs(down);
        verify(writer).markFailed(TOPUP);
        verify(writer, never()).markPending(any(), any());
    }

    @Test
    void gatewayRejectionMarksFailedAndRethrows() {
        GatewayRejectedException rejected = new GatewayRejectedException(400, "BAD_REQUEST_ERROR");
        when(gateway.createOrder(any(), any())).thenThrow(rejected);
        when(writer.markFailed(TOPUP)).thenReturn(failed());

        assertThatThrownBy(() -> service.create(USER, AMOUNT)).isSameAs(rejected);
        verify(writer).markFailed(TOPUP);
        verify(writer, never()).markPending(any(), any());
    }

    @Test
    void aFailureToMarkFailedDoesNotHideTheGatewayError() {
        GatewayUnavailableException down = new GatewayUnavailableException(null);
        when(gateway.createOrder(any(), any())).thenThrow(down);
        IllegalStateException dbDown = new IllegalStateException("database unavailable");
        doThrow(dbDown).when(writer).markFailed(TOPUP);

        assertThatThrownBy(() -> service.create(USER, AMOUNT)).isSameAs(down);
        assertThat(down.getSuppressed()).containsExactly(dbDown);
    }

    @Test
    void createRefusesToRunInsideATransaction() throws NoSuchMethodException {
        Transactional tx = TopupService.class.getMethod("create", UUID.class, Money.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.NEVER);
    }
}
