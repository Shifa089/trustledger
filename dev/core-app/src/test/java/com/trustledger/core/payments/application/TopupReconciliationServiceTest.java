package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.application.TopupSettlementService.DueTopup;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.GatewayRejectedException;
import com.trustledger.core.payments.domain.GatewayUnavailableException;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.shared.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TopupReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final TopupReconcilerProperties PROPERTIES = new TopupReconcilerProperties("0 */5 * * * *",
            Duration.ofMinutes(15), Duration.ofHours(24), 100, Duration.ofMinutes(10));
    private static final Money AMOUNT = Money.parse("500.00");

    private final TopupSettlementService settlement = mock(TopupSettlementService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final TopupReconciliationService service = new TopupReconciliationService(settlement, gateway,
            PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    private static DueTopup due(String order, Duration age) {
        return new DueTopup(UUID.randomUUID(), order, NOW.minus(age));
    }

    private static GatewayPayment payment(String id, String order, GatewayPaymentStatus status) {
        return new GatewayPayment(id, order, AMOUNT, status, null);
    }

    private void dueAre(DueTopup... topups) {
        when(settlement.duePending(NOW.minus(Duration.ofMinutes(15)), 100)).thenReturn(List.of(topups));
    }

    @Test
    void aCapturedPaymentIsSettled() {
        DueTopup topup = due("order_A0000000001", Duration.ofMinutes(20));
        dueAre(topup);
        GatewayPayment paid = payment("pay_A0000000002", topup.gatewayOrderId(), GatewayPaymentStatus.CAPTURED);
        when(gateway.fetchOrderPayments(topup.gatewayOrderId())).thenReturn(List.of(
                payment("pay_A0000000001", topup.gatewayOrderId(), GatewayPaymentStatus.FAILED), paid));
        when(settlement.capture(paid)).thenReturn(SettlementOutcome.CAPTURED);

        TopupReconciliationReport report = service.reconcile();

        verify(settlement).capture(paid);
        verify(settlement, never()).expire(any());
        assertThat(report).isEqualTo(new TopupReconciliationReport(1, 1, 0, 0, 0, 0, 0));
    }

    @Test
    void aCapturedPaymentWithoutItsOrderIdIsSettledForTheLookedUpOrder() {
        DueTopup topup = due("order_A0000000001", Duration.ofMinutes(20));
        dueAre(topup);
        when(gateway.fetchOrderPayments(topup.gatewayOrderId())).thenReturn(List.of(
                payment("pay_A0000000002", null, GatewayPaymentStatus.CAPTURED)));
        GatewayPayment expected = payment("pay_A0000000002", topup.gatewayOrderId(), GatewayPaymentStatus.CAPTURED);
        when(settlement.capture(expected)).thenReturn(SettlementOutcome.PARKED);

        assertThat(service.reconcile().parked()).isEqualTo(1);
        verify(settlement).capture(expected);
    }

    @Test
    void nothingCapturedAndYoungIsLeftPending() {
        DueTopup topup = due("order_A0000000001", Duration.ofHours(23));
        dueAre(topup);
        when(gateway.fetchOrderPayments(topup.gatewayOrderId())).thenReturn(List.of(
                payment("pay_A0000000001", topup.gatewayOrderId(), GatewayPaymentStatus.FAILED),
                payment("pay_A0000000003", topup.gatewayOrderId(), GatewayPaymentStatus.AUTHORIZED)));

        assertThat(service.reconcile()).isEqualTo(new TopupReconciliationReport(1, 0, 0, 0, 1, 0, 0));
        verify(settlement, never()).capture(any());
        verify(settlement, never()).expire(any());
    }

    @Test
    void nothingCapturedAfter24HoursExpires() {
        DueTopup topup = due("order_A0000000001", Duration.ofHours(24).plusSeconds(1));
        dueAre(topup);
        when(gateway.fetchOrderPayments(topup.gatewayOrderId())).thenReturn(List.of());
        when(settlement.expire(topup.topupId())).thenReturn(SettlementOutcome.EXPIRED);

        assertThat(service.reconcile()).isEqualTo(new TopupReconciliationReport(1, 0, 0, 1, 0, 0, 0));
        verify(settlement).expire(topup.topupId());
    }

    @Test
    void aGatewayErrorSkipsThatTopupOnlyAndNeverExpiresIt() {
        DueTopup down = due("order_A0000000001", Duration.ofDays(2));
        DueTopup refused = due("order_B0000000001", Duration.ofDays(2));
        DueTopup ok = due("order_C0000000001", Duration.ofDays(2));
        dueAre(down, refused, ok);
        when(gateway.fetchOrderPayments(down.gatewayOrderId()))
                .thenThrow(new GatewayUnavailableException(new IllegalStateException("timeout")));
        when(gateway.fetchOrderPayments(refused.gatewayOrderId()))
                .thenThrow(new GatewayRejectedException(404, "BAD_REQUEST_ERROR"));
        when(gateway.fetchOrderPayments(ok.gatewayOrderId())).thenReturn(List.of());
        when(settlement.expire(ok.topupId())).thenReturn(SettlementOutcome.EXPIRED);

        assertThat(service.reconcile()).isEqualTo(new TopupReconciliationReport(3, 0, 0, 1, 0, 2, 0));
        verify(settlement, never()).expire(down.topupId());
        verify(settlement, never()).expire(refused.topupId());
    }

    @Test
    void aSettlementFailureIsCountedAndTheRunContinues() {
        DueTopup broken = due("order_A0000000001", Duration.ofDays(2));
        DueTopup ok = due("order_B0000000001", Duration.ofDays(2));
        dueAre(broken, ok);
        when(gateway.fetchOrderPayments(any())).thenReturn(List.of());
        when(settlement.expire(broken.topupId())).thenThrow(new IllegalStateException("boom"));
        when(settlement.expire(ok.topupId())).thenReturn(SettlementOutcome.EXPIRED);

        assertThat(service.reconcile()).isEqualTo(new TopupReconciliationReport(2, 0, 0, 1, 0, 0, 1));
    }

    @Test
    void settledMeanwhileIsNotCounted() {
        DueTopup topup = due("order_A0000000001", Duration.ofDays(2));
        dueAre(topup);
        when(gateway.fetchOrderPayments(any())).thenReturn(List.of());
        when(settlement.expire(topup.topupId())).thenReturn(SettlementOutcome.IGNORED);

        assertThat(service.reconcile()).isEqualTo(new TopupReconciliationReport(1, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void nothingDueMeansNoGatewayCalls() {
        dueAre();
        assertThat(service.reconcile()).isEqualTo(new TopupReconciliationReport(0, 0, 0, 0, 0, 0, 0));
        verifyNoInteractions(gateway);
    }

    @Test
    void runsWithoutATransactionSoTheGatewayIsNeverCalledInsideOne() throws NoSuchMethodException {
        Transactional tx = TopupReconciliationService.class.getMethod("reconcile")
                .getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.NEVER);
    }
}
