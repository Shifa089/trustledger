package com.trustledger.core.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransferTest {

    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECEIVER = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    private static Transfer initiated() {
        return Transfer.initiate(SENDER, RECEIVER, Money.parse("1500.50"), "  rent  ", T0);
    }

    private static Transfer held() {
        Transfer transfer = initiated();
        transfer.holdForReview(T0);
        return transfer;
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    @Test
    void initiateCreatesAnInitiatedTransfer() {
        Transfer transfer = initiated();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.INITIATED);
        assertThat(transfer.amount()).isEqualTo(Money.of(150_050, Money.INR));
        assertThat(transfer.getSenderWalletId()).isEqualTo(SENDER);
        assertThat(transfer.getReceiverWalletId()).isEqualTo(RECEIVER);
        assertThat(transfer.getNote()).isEqualTo("rent");
        assertThat(transfer.getCreatedAt()).isEqualTo(T0);
        assertThat(transfer.getUpdatedAt()).isEqualTo(T0);
        assertThat(transfer.getJournalEntryId()).isNull();
        assertThat(transfer.getReviewedBy()).isNull();
        assertThat(transfer.getReviewedAt()).isNull();
    }

    @Test
    void blankNoteIsNoNote() {
        assertThat(Transfer.initiate(SENDER, RECEIVER, Money.parse("1.00"), "   ", T0).getNote()).isNull();
        assertThat(Transfer.initiate(SENDER, RECEIVER, Money.parse("1.00"), null, T0).getNote()).isNull();
    }

    @Test
    void noteLengthIsLimited() {
        String max = "x".repeat(Transfer.MAX_NOTE_LENGTH);

        assertThat(Transfer.initiate(SENDER, RECEIVER, Money.parse("1.00"), max, T0).getNote()).isEqualTo(max);
        refused(() -> Transfer.initiate(SENDER, RECEIVER, Money.parse("1.00"), max + "x", T0),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void selfTransferIsRefused() {
        refused(() -> Transfer.initiate(SENDER, SENDER, Money.parse("1.00"), null, T0), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void zeroAmountIsRefused() {
        refused(() -> Transfer.initiate(SENDER, RECEIVER, Money.zero(Money.INR), null, T0),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void otherCurrencyIsRefused() {
        refused(() -> Transfer.initiate(SENDER, RECEIVER, Money.of(100, Currency.getInstance("USD")), null, T0),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void missingArgumentsAreBugs() {
        assertThatNullPointerException().isThrownBy(() -> Transfer.initiate(null, RECEIVER, Money.parse("1"), null, T0));
        assertThatNullPointerException().isThrownBy(() -> Transfer.initiate(SENDER, null, Money.parse("1"), null, T0));
        assertThatNullPointerException().isThrownBy(() -> Transfer.initiate(SENDER, RECEIVER, null, null, T0));
        assertThatNullPointerException().isThrownBy(() -> Transfer.initiate(SENDER, RECEIVER, Money.parse("1"), null,
                null));
        assertThatNullPointerException().isThrownBy(() -> initiated().complete(null, T1));
        assertThatNullPointerException().isThrownBy(() -> held().approve(null, ENTRY, T1));
        assertThatNullPointerException().isThrownBy(() -> held().approve(OFFICER, null, T1));
        assertThatNullPointerException().isThrownBy(() -> held().reject(null, T1));
    }

    @Test
    void initiatedCompletes() {
        Transfer transfer = initiated();

        transfer.complete(ENTRY, T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getJournalEntryId()).isEqualTo(ENTRY);
        assertThat(transfer.getReviewedBy()).isNull();
        assertThat(transfer.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void initiatedIsHeldForReview() {
        Transfer transfer = initiated();

        transfer.holdForReview(T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
        assertThat(transfer.getJournalEntryId()).isNull();
        assertThat(transfer.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void initiatedFails() {
        Transfer transfer = initiated();

        transfer.fail(T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(transfer.getJournalEntryId()).isNull();
    }

    @Test
    void heldIsApproved() {
        Transfer transfer = held();

        transfer.approve(OFFICER, ENTRY, T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getJournalEntryId()).isEqualTo(ENTRY);
        assertThat(transfer.getReviewedBy()).isEqualTo(OFFICER);
        assertThat(transfer.getReviewedAt()).isEqualTo(T1);
    }

    @Test
    void heldIsRejected() {
        Transfer transfer = held();

        transfer.reject(OFFICER, T1);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(transfer.getJournalEntryId()).isNull();
        assertThat(transfer.getReviewedBy()).isEqualTo(OFFICER);
        assertThat(transfer.getReviewedAt()).isEqualTo(T1);
    }

    // --- every step that is not in the state machine ---

    private static final Map<String, Supplier<Transfer>> STATES = Map.of(
            "INITIATED", TransferTest::initiated,
            "HELD_FOR_REVIEW", TransferTest::held,
            "COMPLETED", () -> {
                Transfer t = initiated();
                t.complete(ENTRY, T0);
                return t;
            },
            "REJECTED", () -> {
                Transfer t = held();
                t.reject(OFFICER, T0);
                return t;
            },
            "FAILED", () -> {
                Transfer t = initiated();
                t.fail(T0);
                return t;
            });

    private static final Map<String, Consumer<Transfer>> STEPS = Map.of(
            "complete", t -> t.complete(ENTRY, T1),
            "holdForReview", t -> t.holdForReview(T1),
            "fail", t -> t.fail(T1),
            "approve", t -> t.approve(OFFICER, ENTRY, T1),
            "reject", t -> t.reject(OFFICER, T1));

    private static final Map<String, List<String>> ALLOWED = Map.of(
            "INITIATED", List.of("complete", "holdForReview", "fail"),
            "HELD_FOR_REVIEW", List.of("approve", "reject"));

    static Stream<Arguments> forbiddenSteps() {
        List<Arguments> forbidden = new ArrayList<>();
        STATES.keySet().forEach(state -> STEPS.keySet().forEach(step -> {
            if (!ALLOWED.getOrDefault(state, List.of()).contains(step)) {
                forbidden.add(Arguments.of(state, step));
            }
        }));
        return forbidden.stream();
    }

    @ParameterizedTest(name = "{1} from {0} is refused")
    @MethodSource("forbiddenSteps")
    void stepsOutsideTheStateMachineAreRefused(String state, String step) {
        Transfer transfer = STATES.get(state).get();
        TransferStatus before = transfer.getStatus();

        refused(() -> STEPS.get(step).accept(transfer), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(transfer.getStatus()).isEqualTo(before);
    }

    @Test
    void involvesSenderAndReceiverOnly() {
        Transfer transfer = initiated();

        assertThat(transfer.involves(SENDER)).isTrue();
        assertThat(transfer.involves(RECEIVER)).isTrue();
        assertThat(transfer.involves(UUID.randomUUID())).isFalse();
    }
}
