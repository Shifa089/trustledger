package com.trustledger.core.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentGatewayTypesTest {

    @ParameterizedTest
    @CsvSource({"created, CREATED", "authorized, AUTHORIZED", "CAPTURED, CAPTURED", "refunded, REFUNDED",
            "failed, FAILED", "pending_review, UNKNOWN", "'', UNKNOWN"})
    void mapsGatewayStatuses(String value, GatewayPaymentStatus expected) {
        assertThat(GatewayPaymentStatus.fromGateway(value)).isEqualTo(expected);
    }

    @Test
    void nullStatusIsUnknown() {
        assertThat(GatewayPaymentStatus.fromGateway(null)).isEqualTo(GatewayPaymentStatus.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay_", "pay_abc/../x", "order_ABC", "pay_ABC?x=1", "PAY_ABC", " pay_ABC"})
    @NullSource
    void rejectsMalformedPaymentIds(String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentGateway.requirePaymentId(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_", "order_a b", "pay_ABC", "order_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcde"})
    @NullSource
    void rejectsMalformedOrderIds(String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentGateway.requireOrderId(id));
    }

    @Test
    void acceptsRazorpayStyleIds() {
        assertThat(PaymentGateway.requirePaymentId("pay_29QQoUBi66xm2f")).isEqualTo("pay_29QQoUBi66xm2f");
        assertThat(PaymentGateway.requireOrderId("order_EKwxwAgItmmXdp")).isEqualTo("order_EKwxwAgItmmXdp");
    }

    @Test
    void recordsCheckTheirIds() {
        Money amount = Money.of(100, Money.INR);
        assertThatIllegalArgumentException().isThrownBy(() -> new GatewayOrder("bad", amount, "r", "created"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new GatewayPayment("bad", null, amount, GatewayPaymentStatus.CAPTURED, null));
    }

    @Test
    void exceptionsCarryTheirApiCodes() {
        GatewayUnavailableException unavailable = new GatewayUnavailableException(new RuntimeException("boom-cause"));
        GatewayRejectedException rejected = new GatewayRejectedException(400, "BAD_REQUEST_ERROR");

        assertThat(unavailable.errorCode()).isEqualTo(ErrorCode.GATEWAY_UNAVAILABLE);
        assertThat(unavailable.errorCode().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.detail()).doesNotContain("boom");
        assertThat(rejected.errorCode().status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(new GatewayUnavailableException(null)).hasNoCause();
    }
}
