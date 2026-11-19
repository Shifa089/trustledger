package com.trustledger.core.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TopupTest {

    private static final UUID WALLET = UUID.randomUUID();
    private static final Money AMOUNT = Money.parse("500.00");
    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(5);
    private static final String ORDER = "order_TEST00000001";
    private static final String PAYMENT = "pay_TEST00000001";
    private static final UUID ENTRY = UUID.randomUUID();

    private static Topup created() {
        return Topup.create(WALLET, AMOUNT, T0);
    }

    private static Topup pending() {
        Topup topup = created();
        topup.markPending(ORDER, T0);
        return topup;
    }

    private static Topup in(TopupStatus status) {
        return switch (status) {
            case CREATED -> created();
            case PENDING -> pending();
            case CAPTURED -> {
                Topup topup = pending();
                topup.capture(PAYMENT, ENTRY, T0);
                yield topup;
            }
            case FAILED -> {
                Topup topup = created();
                topup.fail(T0);
                yield topup;
            }
            case EXPIRED -> {
                Topup topup = pending();
                topup.expire(T0);
                yield topup;
            }
        };
    }

    private static void invalidTransition(Topup topup, Consumer<Topup> step) {
        TopupStatus before = topup.getStatus();
        assertThatThrownBy(() -> step.accept(topup)).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(topup.getStatus()).isEqualTo(before);
    }

    // --- creation ---

    @Test
    void createIsCreatedWithTheAmountAndNoGatewayIds() {
        Topup topup = created();

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CREATED);
        assertThat(topup.getWalletId()).isEqualTo(WALLET);
        assertThat(topup.amount()).isEqualTo(AMOUNT);
        assertThat(topup.getGatewayOrderId()).isNull();
        assertThat(topup.getGatewayPaymentId()).isNull();
        assertThat(topup.getJournalEntryId()).isNull();
        assertThat(topup.getCreatedAt()).isEqualTo(T0);
        assertThat(topup.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void createRefusesZeroAndForeignCurrency() {
        assertThatThrownBy(() -> Topup.create(WALLET, Money.zero(Money.INR), T0))
                .isInstanceOf(TrustLedgerException.class).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> Topup.create(WALLET, Money.of(100, Currency.getInstance("USD")), T0))
                .isInstanceOf(TrustLedgerException.class).hasMessageContaining("INR");
    }

    @Test
    void createNeedsWalletAmountAndTime() {
        assertThatNullPointerException().isThrownBy(() -> Topup.create(null, AMOUNT, T0));
        assertThatNullPointerException().isThrownBy(() -> Topup.create(WALLET, null, T0));
        assertThatNullPointerException().isThrownBy(() -> Topup.create(WALLET, AMOUNT, null));
    }

    // --- allowed transitions ---

    @Test
    void markPendingRecordsTheOrder() {
        Topup topup = created();
        topup.markPending(ORDER, T1);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.PENDING);
        assertThat(topup.getGatewayOrderId()).isEqualTo(ORDER);
        assertThat(topup.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void createdCanFailWithoutAnOrder() {
        Topup topup = created();
        topup.fail(T1);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED);
        assertThat(topup.getGatewayOrderId()).isNull();
        assertThat(topup.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void pendingCanFailAndKeepsItsOrder() {
        Topup topup = pending();
        topup.fail(T1);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.FAILED);
        assertThat(topup.getGatewayOrderId()).isEqualTo(ORDER);
    }

    @Test
    void captureRecordsPaymentAndJournalEntry() {
        Topup topup = pending();
        topup.capture(PAYMENT, ENTRY, T1);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.CAPTURED);
        assertThat(topup.getGatewayPaymentId()).isEqualTo(PAYMENT);
        assertThat(topup.getJournalEntryId()).isEqualTo(ENTRY);
        assertThat(topup.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void pendingCanExpire() {
        Topup topup = pending();
        topup.expire(T1);

        assertThat(topup.getStatus()).isEqualTo(TopupStatus.EXPIRED);
        assertThat(topup.getUpdatedAt()).isEqualTo(T1);
    }

    // --- refused transitions ---

    @ParameterizedTest
    @EnumSource(value = TopupStatus.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    void onlyCreatedCanBecomePending(TopupStatus status) {
        invalidTransition(in(status), t -> t.markPending("order_OTHER0000001", T1));
    }

    @ParameterizedTest
    @EnumSource(value = TopupStatus.class, names = {"CAPTURED", "FAILED", "EXPIRED"})
    void finishedTopupsCannotFail(TopupStatus status) {
        invalidTransition(in(status), t -> t.fail(T1));
    }

    @ParameterizedTest
    @EnumSource(value = TopupStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void onlyPendingCanBeCaptured(TopupStatus status) {
        invalidTransition(in(status), t -> t.capture(PAYMENT, ENTRY, T1));
    }

    @ParameterizedTest
    @EnumSource(value = TopupStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void onlyPendingCanExpire(TopupStatus status) {
        invalidTransition(in(status), t -> t.expire(T1));
    }

    // --- argument checks ---

    @Test
    void gatewayIdsAreValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> created().markPending("pay_NOTANORDER01", T1));
        assertThatIllegalArgumentException().isThrownBy(() -> created().markPending(null, T1));
        assertThatIllegalArgumentException().isThrownBy(() -> pending().capture("order_NOTAPAY0001", ENTRY, T1));
        assertThatNullPointerException().isThrownBy(() -> pending().capture(PAYMENT, null, T1));
    }

    @Test
    void ownershipIsByWallet() {
        assertThat(created().belongsTo(WALLET)).isTrue();
        assertThat(created().belongsTo(UUID.randomUUID())).isFalse();
    }

    @Test
    void openAndCountedSetsMatchTheLimitRules() {
        assertThat(TopupStatus.OPEN).containsExactlyInAnyOrder(TopupStatus.CREATED, TopupStatus.PENDING);
        assertThat(TopupStatus.COUNTED_FOR_LOAD)
                .containsExactlyInAnyOrder(TopupStatus.CREATED, TopupStatus.PENDING, TopupStatus.CAPTURED);
    }
}
