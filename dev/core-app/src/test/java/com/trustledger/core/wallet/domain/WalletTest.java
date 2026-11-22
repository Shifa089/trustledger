package com.trustledger.core.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class WalletTest {

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final UUID USER = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();

    private static Money inr(long minor) {
        return Money.of(minor, Money.INR);
    }

    private static Wallet wallet() {
        return Wallet.open(USER, WalletTier.MIN, T0);
    }

    /** A wallet with a balance, built through credit (the only way money gets in). */
    private static Wallet walletWith(long minor) {
        Wallet wallet = wallet();
        wallet.credit(inr(minor), T0);
        return wallet;
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    @Test
    void opensAnEmptyActiveInrWallet() {
        Wallet wallet = wallet();

        assertThat(wallet.getUserId()).isEqualTo(USER);
        assertThat(wallet.getTier()).isEqualTo(WalletTier.MIN);
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.getCurrency()).isEqualTo(Money.INR);
        assertThat(wallet.balance()).isEqualTo(inr(0));
        assertThat(wallet.held()).isEqualTo(inr(0));
        assertThat(wallet.available()).isEqualTo(inr(0));
        assertThat(wallet.getCreatedAt()).isEqualTo(T0);
        assertThat(wallet.getUpdatedAt()).isEqualTo(T0);
        assertThat(wallet.freezeHistory()).isEmpty();
        assertThat(wallet.currentFreeze()).isEmpty();
        assertThat(wallet.isOwnedBy(USER)).isTrue();
        assertThat(wallet.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    @Test
    void openNeedsUserTierAndTime() {
        assertThatNullPointerException().isThrownBy(() -> Wallet.open(null, WalletTier.MIN, T0));
        assertThatNullPointerException().isThrownBy(() -> Wallet.open(USER, null, T0));
        assertThatNullPointerException().isThrownBy(() -> Wallet.open(USER, WalletTier.MIN, null));
    }

    @Nested
    class Tier {

        @Test
        void minUpgradesToFull() {
            Wallet wallet = wallet();

            assertThat(wallet.upgradeTo(WalletTier.FULL, T1)).isTrue();

            assertThat(wallet.getTier()).isEqualTo(WalletTier.FULL);
            assertThat(wallet.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        void sameTierIsANoOp() {
            Wallet wallet = wallet();

            assertThat(wallet.upgradeTo(WalletTier.MIN, T1)).isFalse();
            assertThat(wallet.getUpdatedAt()).isEqualTo(T0);
        }

        @Test
        void neverDowngrades() {
            Wallet wallet = Wallet.open(USER, WalletTier.FULL, T0);

            assertThat(wallet.upgradeTo(WalletTier.MIN, T1)).isFalse();
            assertThat(wallet.getTier()).isEqualTo(WalletTier.FULL);
        }
    }

    @Nested
    class Movements {

        @Test
        void creditAddsToBalanceAndAvailable() {
            Wallet wallet = wallet();

            wallet.credit(inr(150_050), T1);

            assertThat(wallet.balance()).isEqualTo(inr(150_050));
            assertThat(wallet.available()).isEqualTo(inr(150_050));
            assertThat(wallet.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        void debitTakesFromBalance() {
            Wallet wallet = walletWith(10_000);

            wallet.debit(inr(2_500), T1);

            assertThat(wallet.balance()).isEqualTo(inr(7_500));
        }

        @Test
        void debitOfTheWholeAvailableBalanceIsAllowed() {
            Wallet wallet = walletWith(10_000);

            wallet.debit(inr(10_000), T1);

            assertThat(wallet.balance()).isEqualTo(inr(0));
        }

        @Test
        void debitAboveAvailableIsInsufficientFundsAndChangesNothing() {
            Wallet wallet = walletWith(10_000);

            assertCode(() -> wallet.debit(inr(10_001), T1), ErrorCode.INSUFFICIENT_FUNDS);

            assertThat(wallet.balance()).isEqualTo(inr(10_000));
            assertThat(wallet.getUpdatedAt()).isEqualTo(T0);
        }

        @Test
        void heldMoneyCannotBeDebited() {
            Wallet wallet = walletWith(10_000);
            wallet.hold(inr(6_000), T1);

            assertCode(() -> wallet.debit(inr(4_001), T1), ErrorCode.INSUFFICIENT_FUNDS);
            wallet.debit(inr(4_000), T1);

            assertThat(wallet.balance()).isEqualTo(inr(6_000));
            assertThat(wallet.held()).isEqualTo(inr(6_000));
            assertThat(wallet.available()).isEqualTo(inr(0));
        }

        @Test
        void holdReducesAvailableButNotBalance() {
            Wallet wallet = walletWith(10_000);

            wallet.hold(inr(3_000), T1);

            assertThat(wallet.balance()).isEqualTo(inr(10_000));
            assertThat(wallet.held()).isEqualTo(inr(3_000));
            assertThat(wallet.available()).isEqualTo(inr(7_000));
        }

        @Test
        void holdAboveAvailableIsInsufficientFunds() {
            Wallet wallet = walletWith(10_000);
            wallet.hold(inr(8_000), T1);

            assertCode(() -> wallet.hold(inr(2_001), T1), ErrorCode.INSUFFICIENT_FUNDS);

            assertThat(wallet.held()).isEqualTo(inr(8_000));
        }

        @Test
        void releaseHoldGivesAvailableBack() {
            Wallet wallet = walletWith(10_000);
            wallet.hold(inr(3_000), T1);

            wallet.releaseHold(inr(1_000), T1);

            assertThat(wallet.held()).isEqualTo(inr(2_000));
            assertThat(wallet.available()).isEqualTo(inr(8_000));
        }

        @Test
        void releasingMoreThanHeldIsACallerBug() {
            Wallet wallet = walletWith(10_000);
            wallet.hold(inr(3_000), T1);

            assertThatIllegalArgumentException().isThrownBy(() -> wallet.releaseHold(inr(3_001), T1));

            assertThat(wallet.held()).isEqualTo(inr(3_000));
        }

        @Test
        void zeroAmountIsRejected() {
            Wallet wallet = walletWith(10_000);
            wallet.hold(inr(1_000), T1);

            assertCode(() -> wallet.credit(inr(0), T1), ErrorCode.VALIDATION_FAILED);
            assertCode(() -> wallet.debit(inr(0), T1), ErrorCode.VALIDATION_FAILED);
            assertCode(() -> wallet.hold(inr(0), T1), ErrorCode.VALIDATION_FAILED);
            assertCode(() -> wallet.releaseHold(inr(0), T1), ErrorCode.VALIDATION_FAILED);
        }

        @Test
        void anotherCurrencyIsRejected() {
            Wallet wallet = walletWith(10_000);

            assertCode(() -> wallet.credit(Money.of(100, Currency.getInstance("USD")), T1),
                    ErrorCode.VALIDATION_FAILED);
            assertThat(wallet.balance()).isEqualTo(inr(10_000));
        }

        @Test
        void nullAmountIsAProgrammingError() {
            assertThatNullPointerException().isThrownBy(() -> wallet().credit(null, T1));
        }

        @Test
        void overflowNeverWrapsAround() {
            Wallet wallet = walletWith(Long.MAX_VALUE);

            assertThatThrownBy(() -> wallet.credit(inr(1), T1)).isInstanceOf(ArithmeticException.class);

            assertThat(wallet.balance()).isEqualTo(inr(Long.MAX_VALUE));
        }
    }

    @Nested
    class Frozen {

        @Test
        void frozenWalletCannotSendReceiveOrHold() {
            Wallet wallet = walletWith(10_000);
            wallet.freeze("Suspicious activity", FreezeSource.COMPLIANCE, OFFICER, T1);

            assertCode(() -> wallet.debit(inr(1), T1), ErrorCode.WALLET_FROZEN);
            assertCode(() -> wallet.credit(inr(1), T1), ErrorCode.WALLET_FROZEN);
            assertCode(() -> wallet.hold(inr(1), T1), ErrorCode.WALLET_FROZEN);

            assertThat(wallet.balance()).isEqualTo(inr(10_000));
        }

        @Test
        void frozenWalletCanStillReleaseAHold() {
            Wallet wallet = walletWith(10_000);
            wallet.hold(inr(5_000), T0);
            wallet.freeze("Suspicious activity", FreezeSource.AML, null, T1);

            wallet.releaseHold(inr(5_000), T1);

            assertThat(wallet.held()).isEqualTo(inr(0));
        }

        @Test
        void closedWalletRefusesEverything() {
            Wallet wallet = walletWith(10_000);
            wallet.hold(inr(1_000), T0);
            ReflectionTestUtils.setField(wallet, "status", WalletStatus.CLOSED);

            assertCode(() -> wallet.debit(inr(1), T1), ErrorCode.INVALID_STATE_TRANSITION);
            assertCode(() -> wallet.credit(inr(1), T1), ErrorCode.INVALID_STATE_TRANSITION);
            assertCode(() -> wallet.hold(inr(1), T1), ErrorCode.INVALID_STATE_TRANSITION);
            assertCode(() -> wallet.releaseHold(inr(1), T1), ErrorCode.INVALID_STATE_TRANSITION);
            assertCode(() -> wallet.freeze("x", FreezeSource.COMPLIANCE, OFFICER, T1),
                    ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    @Nested
    class FreezeAndUnfreeze {

        @Test
        void freezeRecordsWhoWhyAndWhen() {
            Wallet wallet = wallet();

            WalletFreeze freeze = wallet.freeze("  Bank fraud report  ", FreezeSource.COMPLIANCE, OFFICER, T1);

            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.FROZEN);
            assertThat(wallet.getUpdatedAt()).isEqualTo(T1);
            assertThat(freeze.getSource()).isEqualTo(FreezeSource.COMPLIANCE);
            assertThat(freeze.getReason()).isEqualTo("Bank fraud report");
            assertThat(freeze.getFrozenBy()).isEqualTo(OFFICER);
            assertThat(freeze.getFrozenAt()).isEqualTo(T1);
            assertThat(freeze.isOpen()).isTrue();
            assertThat(wallet.currentFreeze()).containsSame(freeze);
            assertThat(wallet.freezeHistory()).containsExactly(freeze);
        }

        @Test
        void amlFreezeNeedsNoOfficerButComplianceDoes() {
            assertThat(wallet().freeze("R2 structuring", FreezeSource.AML, null, T1).getFrozenBy()).isNull();
            assertThatNullPointerException()
                    .isThrownBy(() -> wallet().freeze("reason", FreezeSource.COMPLIANCE, null, T1));
        }

        @Test
        void freezingAFrozenWalletIsAnInvalidTransition() {
            Wallet wallet = wallet();
            wallet.freeze("first", FreezeSource.COMPLIANCE, OFFICER, T1);

            assertCode(() -> wallet.freeze("second", FreezeSource.AML, null, T1), ErrorCode.INVALID_STATE_TRANSITION);

            assertThat(wallet.freezeHistory()).hasSize(1);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void freezeNeedsAReason(String reason) {
            Wallet wallet = wallet();

            assertCode(() -> wallet.freeze(reason, FreezeSource.COMPLIANCE, OFFICER, T1), ErrorCode.VALIDATION_FAILED);

            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        }

        @Test
        void reasonOfAtMost500Characters() {
            wallet().freeze("r".repeat(500), FreezeSource.COMPLIANCE, OFFICER, T1);
            assertCode(() -> wallet().freeze("r".repeat(501), FreezeSource.COMPLIANCE, OFFICER, T1),
                    ErrorCode.VALIDATION_FAILED);
        }

        @Test
        void unfreezeClosesTheOpenFreeze() {
            Wallet wallet = walletWith(1_000);
            WalletFreeze freeze = wallet.freeze("check", FreezeSource.COMPLIANCE, OFFICER, T0);
            UUID secondOfficer = UUID.randomUUID();

            WalletFreeze closed = wallet.unfreeze(secondOfficer, " Cleared after review ", T1);

            assertThat(closed).isSameAs(freeze);
            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
            assertThat(freeze.isOpen()).isFalse();
            assertThat(freeze.getUnfrozenBy()).isEqualTo(secondOfficer);
            assertThat(freeze.getUnfrozenAt()).isEqualTo(T1);
            assertThat(freeze.getUnfreezeReason()).isEqualTo("Cleared after review");
            assertThat(wallet.currentFreeze()).isEmpty();
            wallet.debit(inr(1_000), T1); // money moves again
        }

        @Test
        void theSameOfficerMayUnfreezeAnotherUsersWallet() {
            Wallet wallet = wallet();
            wallet.freeze("check", FreezeSource.COMPLIANCE, OFFICER, T0);

            wallet.unfreeze(OFFICER, "false alarm", T1);

            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        }

        @Test
        void freezeUnfreezeFreezeKeepsTheHistory() {
            Wallet wallet = wallet();
            wallet.freeze("one", FreezeSource.COMPLIANCE, OFFICER, T0);
            wallet.unfreeze(OFFICER, "ok", T0);
            wallet.freeze("two", FreezeSource.AML, null, T1);

            assertThat(wallet.freezeHistory()).extracting(WalletFreeze::getReason).containsExactly("one", "two");
            assertThat(wallet.currentFreeze()).get().extracting(WalletFreeze::getReason).isEqualTo("two");
        }

        @Test
        void unfreezingAnActiveWalletIsAnInvalidTransition() {
            assertCode(() -> wallet().unfreeze(OFFICER, "why", T1), ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        void anOfficerCannotUnfreezeTheirOwnWallet() {
            Wallet wallet = wallet();
            wallet.freeze("check", FreezeSource.COMPLIANCE, OFFICER, T0);

            assertCode(() -> wallet.unfreeze(USER, "it's mine", T1), ErrorCode.MAKER_CHECKER_VIOLATION);

            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.FROZEN);
        }

        @Test
        void unfreezeNeedsAReason() {
            Wallet wallet = wallet();
            wallet.freeze("check", FreezeSource.COMPLIANCE, OFFICER, T0);

            assertCode(() -> wallet.unfreeze(OFFICER, " ", T1), ErrorCode.VALIDATION_FAILED);

            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.FROZEN);
        }

        @Test
        void historyIsReadOnly() {
            Wallet wallet = wallet();
            wallet.freeze("check", FreezeSource.COMPLIANCE, OFFICER, T0);

            assertThatThrownBy(() -> wallet.freezeHistory().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class LedgerLink {

        @Test
        void linksOnceAndTouches() {
            Wallet wallet = wallet();
            UUID account = UUID.randomUUID();

            assertThat(wallet.linkLedgerAccount(account, T1)).isTrue();

            assertThat(wallet.getLedgerAccountId()).isEqualTo(account);
            assertThat(wallet.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        void linkingTheSameAccountAgainChangesNothing() {
            Wallet wallet = wallet();
            UUID account = UUID.randomUUID();
            wallet.linkLedgerAccount(account, T0);

            assertThat(wallet.linkLedgerAccount(account, T1)).isFalse();
            assertThat(wallet.getUpdatedAt()).isEqualTo(T0);
        }

        @Test
        void anotherAccountIsABug() {
            Wallet wallet = wallet();
            UUID account = UUID.randomUUID();
            wallet.linkLedgerAccount(account, T0);

            assertThatThrownBy(() -> wallet.linkLedgerAccount(UUID.randomUUID(), T1))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(wallet.getLedgerAccountId()).isEqualTo(account);
        }

        @Test
        void accountIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> wallet().linkLedgerAccount(null, T1));
        }
    }

    @Nested
    class Close {

        @Test
        void anEmptyActiveWalletCloses() {
            Wallet wallet = wallet();

            wallet.close(T1);

            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.CLOSED);
            assertThat(wallet.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        void aWalletWithMoneyIsRefusedAndStaysActive() {
            Wallet wallet = walletWith(1);

            assertCode(() -> wallet.close(T1), ErrorCode.WALLET_NOT_EMPTY);
            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        }

        @Test
        void aWalletWithAHoldIsRefused() {
            Wallet wallet = walletWith(500);
            wallet.hold(inr(500), T0); // available is 0, but the money is still on the wallet

            assertCode(() -> wallet.close(T1), ErrorCode.WALLET_NOT_EMPTY);
        }

        @Test
        void aFrozenWalletStaysUnderItsHold() {
            Wallet wallet = wallet();
            wallet.freeze("AML case, rule R2", FreezeSource.AML, null, T0);

            assertCode(() -> wallet.close(T1), ErrorCode.WALLET_FROZEN);
            assertThat(wallet.getStatus()).isEqualTo(WalletStatus.FROZEN);
        }

        @Test
        void aClosedWalletCantBeClosedAgainOrMoveMoney() {
            Wallet wallet = wallet();
            wallet.close(T1);

            assertCode(() -> wallet.close(T1), ErrorCode.INVALID_STATE_TRANSITION);
            assertCode(() -> wallet.credit(inr(100), T1), ErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
