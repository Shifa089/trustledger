package com.trustledger.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Each contract rejects what a consumer could not process, at construction on the producer side. */
class ContractRecordsTest {

    private static final UUID EVENT = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
    private static final Instant AT = Instant.parse("2026-11-21T10:15:30Z");
    private static final UUID WALLET_A = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID WALLET_B = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");
    private static final UUID REF = UUID.fromString("0190a1b2-0000-7000-8000-0000000000ff");

    @Nested
    class TransferCompleted {

        @Test
        void validEventKeepsItsFields() {
            var event = new TransferCompletedV1(EVENT, AT, WALLET_A, WALLET_B, REF, 150_050, "INR");

            assertThat(event.walletId()).isEqualTo(WALLET_A);
            assertThat(event.counterpartyWalletId()).isEqualTo(WALLET_B);
            assertThat(event.amountMinor()).isEqualTo(150_050);
            assertThat(TransferCompletedV1.TOPIC).isEqualTo("trustledger.payments.transfer-completed.v1");
        }

        @Test
        void rejectsMissingIds() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransferCompletedV1(null, AT, WALLET_A, WALLET_B, REF, 1, "INR"))
                    .withMessage("eventId");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransferCompletedV1(EVENT, null, WALLET_A, WALLET_B, REF, 1, "INR"))
                    .withMessage("occurredAt");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransferCompletedV1(EVENT, AT, null, WALLET_B, REF, 1, "INR"))
                    .withMessage("walletId");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransferCompletedV1(EVENT, AT, WALLET_A, null, REF, 1, "INR"))
                    .withMessage("counterpartyWalletId");
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransferCompletedV1(EVENT, AT, WALLET_A, WALLET_B, null, 1, "INR"))
                    .withMessage("transferId");
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1})
        void rejectsNonPositiveAmount(long amountMinor) {
            assertThatThrownBy(() -> new TransferCompletedV1(EVENT, AT, WALLET_A, WALLET_B, REF, amountMinor, "INR"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsSameWalletOnBothSides() {
            assertThatThrownBy(() -> new TransferCompletedV1(EVENT, AT, WALLET_A, WALLET_A, REF, 1, "INR"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class TopupCaptured {

        @Test
        void validEventKeepsItsFields() {
            var event = new TopupCapturedV1(EVENT, AT, WALLET_A, REF, 500_000, "INR");

            assertThat(event.walletId()).isEqualTo(WALLET_A);
            assertThat(event.topupId()).isEqualTo(REF);
            assertThat(TopupCapturedV1.TOPIC).isEqualTo("trustledger.payments.topup-captured.v1");
        }

        @Test
        void rejectsMissingTopupId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TopupCapturedV1(EVENT, AT, WALLET_A, null, 1, "INR"))
                    .withMessage("topupId");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "inr", "IN", "INRR", "1NR"})
        void rejectsInvalidCurrency(String currency) {
            assertThatThrownBy(() -> new TopupCapturedV1(EVENT, AT, WALLET_A, REF, 1, currency))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsMissingCurrency() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TopupCapturedV1(EVENT, AT, WALLET_A, REF, 1, null))
                    .withMessage("currency");
        }

        @Test
        void rejectsZeroAmount() {
            assertThatThrownBy(() -> new TopupCapturedV1(EVENT, AT, WALLET_A, REF, 0, "INR"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class WalletFrozen {

        @Test
        void validEventKeepsItsFields() {
            var event = new WalletFrozenV1(EVENT, AT, WALLET_A, "AML");

            assertThat(event.source()).isEqualTo("AML");
            assertThat(WalletFrozenV1.TOPIC).isEqualTo("trustledger.wallet.wallet-frozen.v1");
        }

        @Test
        void rejectsBlankOrMissingSource() {
            assertThatThrownBy(() -> new WalletFrozenV1(EVENT, AT, WALLET_A, " "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatNullPointerException()
                    .isThrownBy(() -> new WalletFrozenV1(EVENT, AT, WALLET_A, null))
                    .withMessage("source");
        }

        @Test
        void rejectsMissingWallet() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WalletFrozenV1(EVENT, AT, null, "AML"))
                    .withMessage("walletId");
        }
    }

    @Nested
    class AccountFreezeRequested {

        @Test
        void validEventKeepsItsFields() {
            var event = new AccountFreezeRequestedV1(EVENT, AT, WALLET_A, REF, "R2");

            assertThat(event.caseId()).isEqualTo(REF);
            assertThat(event.ruleCode()).isEqualTo("R2");
            assertThat(AccountFreezeRequestedV1.TOPIC).isEqualTo("trustledger.aml.account-freeze-requested.v1");
        }

        @Test
        void rejectsBlankRuleCodeAndMissingCase() {
            assertThatThrownBy(() -> new AccountFreezeRequestedV1(EVENT, AT, WALLET_A, REF, ""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountFreezeRequestedV1(EVENT, AT, WALLET_A, null, "R2"))
                    .withMessage("caseId");
        }
    }
}
