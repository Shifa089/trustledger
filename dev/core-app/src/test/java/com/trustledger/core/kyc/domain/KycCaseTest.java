package com.trustledger.core.kyc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The KYC state machine: every legal transition, and every illegal one throws. */
class KycCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant T2 = T0.plusSeconds(120);
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID OFFICER_A = UUID.randomUUID();
    private static final UUID OFFICER_B = UUID.randomUUID();
    private static final LocalDate DOB = LocalDate.of(1995, 4, 15);
    private static final String PAN = "ABCPE1234F";
    private static final String PAN_HASH = "a".repeat(64);

    static KycCase min() {
        return KycCase.submitMin(CUSTOMER, "  Test Customer  ", PAN, PAN_HASH, DOB, T0);
    }

    static KycCase full() {
        return KycCase.submitFull(CUSTOMER, " 1 Test Street, Bengaluru ", T0);
    }

    static KycCase fullVerified() {
        KycCase kycCase = full();
        kycCase.verify(OFFICER_A, T1);
        return kycCase;
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode())
                .isEqualTo(code);
    }

    @Nested
    class MinimumKyc {

        @Test
        void submitMinStartsSubmittedWithStrippedName() {
            KycCase kycCase = min();

            assertThat(kycCase.getLevel()).isEqualTo(KycLevel.MIN);
            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.SUBMITTED);
            assertThat(kycCase.getUserId()).isEqualTo(CUSTOMER);
            assertThat(kycCase.getFullName()).isEqualTo("Test Customer");
            assertThat(kycCase.getPan()).isEqualTo(PAN);
            assertThat(kycCase.getPanHash()).isEqualTo(PAN_HASH);
            assertThat(kycCase.getDateOfBirth()).isEqualTo(DOB);
            assertThat(kycCase.getCreatedAt()).isEqualTo(T0);
            assertThat(kycCase.getUpdatedAt()).isEqualTo(T0);
            assertThat(kycCase.isApproved()).isFalse();
        }

        @Test
        void autoApproveMovesSubmittedToApprovedWithoutOfficers() {
            KycCase kycCase = min();

            kycCase.autoApproveMin(T1);

            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.APPROVED);
            assertThat(kycCase.isApproved()).isTrue();
            assertThat(kycCase.getApprovedAt()).isEqualTo(T1);
            assertThat(kycCase.getUpdatedAt()).isEqualTo(T1);
            assertThat(kycCase.getVerifiedBy()).isNull();
            assertThat(kycCase.getApprovedBy()).isNull();
        }

        @Test
        void autoApproveTwiceIsInvalid() {
            KycCase kycCase = min();
            kycCase.autoApproveMin(T1);

            assertError(() -> kycCase.autoApproveMin(T2), ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        void officerStepsAreInvalidForMin() {
            assertError(() -> min().verify(OFFICER_A, T1), ErrorCode.INVALID_STATE_TRANSITION);
            assertError(() -> min().approve(OFFICER_B, T1), ErrorCode.INVALID_STATE_TRANSITION);
            assertError(() -> min().reject(OFFICER_A, "Blurry", T1), ErrorCode.INVALID_STATE_TRANSITION);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "abcpe1234f", "ABCPE1234", "ABCXE1234F", "12345ABCDE"})
        void invalidPanIsRejected(String pan) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> KycCase.submitMin(CUSTOMER, "Test Customer", pan, PAN_HASH, DOB, T0))
                    .withMessageContaining("pan");
        }

        @Test
        void panNeedsItsBlindIndex() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> KycCase.submitMin(CUSTOMER, "Test Customer", PAN, " ", DOB, T0));
        }

        @Test
        void nameIsRequiredAndBounded() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> KycCase.submitMin(CUSTOMER, " ", PAN, PAN_HASH, DOB, T0));
            assertThatIllegalArgumentException().isThrownBy(
                    () -> KycCase.submitMin(CUSTOMER, "x".repeat(101), PAN, PAN_HASH, DOB, T0));
        }

        @Test
        void requiredArguments() {
            assertThatNullPointerException()
                    .isThrownBy(() -> KycCase.submitMin(null, "Test Customer", PAN, PAN_HASH, DOB, T0));
            assertThatNullPointerException()
                    .isThrownBy(() -> KycCase.submitMin(CUSTOMER, "Test Customer", PAN, PAN_HASH, null, T0));
            assertThatNullPointerException()
                    .isThrownBy(() -> KycCase.submitMin(CUSTOMER, "Test Customer", PAN, PAN_HASH, DOB, null));
        }
    }

    @Nested
    class FullKyc {

        @Test
        void submitFullStartsSubmitted() {
            KycCase kycCase = full();

            assertThat(kycCase.getLevel()).isEqualTo(KycLevel.FULL);
            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.SUBMITTED);
            assertThat(kycCase.getAddress()).isEqualTo("1 Test Street, Bengaluru");
            assertThat(kycCase.getPan()).isNull();
        }

        @Test
        void addressIsRequiredAndBounded() {
            assertThatIllegalArgumentException().isThrownBy(() -> KycCase.submitFull(CUSTOMER, null, T0));
            assertThatIllegalArgumentException().isThrownBy(() -> KycCase.submitFull(CUSTOMER, "x".repeat(501), T0));
        }

        @Test
        void verifyMovesSubmittedToVerified() {
            KycCase kycCase = full();

            kycCase.verify(OFFICER_A, T1);

            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.VERIFIED);
            assertThat(kycCase.getVerifiedBy()).isEqualTo(OFFICER_A);
            assertThat(kycCase.getVerifiedAt()).isEqualTo(T1);
            assertThat(kycCase.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        void approveByADifferentOfficerMovesVerifiedToApproved() {
            KycCase kycCase = fullVerified();

            kycCase.approve(OFFICER_B, T2);

            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.APPROVED);
            assertThat(kycCase.getVerifiedBy()).isEqualTo(OFFICER_A);
            assertThat(kycCase.getApprovedBy()).isEqualTo(OFFICER_B);
            assertThat(kycCase.getApprovedAt()).isEqualTo(T2);
        }

        @Test
        void sameOfficerCannotVerifyAndApprove() {
            KycCase kycCase = fullVerified();

            assertError(() -> kycCase.approve(OFFICER_A, T2), ErrorCode.MAKER_CHECKER_VIOLATION);
            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.VERIFIED);
            assertThat(kycCase.getApprovedBy()).isNull();
        }

        @Test
        void officerCannotReviewTheirOwnCase() {
            assertError(() -> full().verify(CUSTOMER, T1), ErrorCode.MAKER_CHECKER_VIOLATION);
            assertError(() -> fullVerified().approve(CUSTOMER, T2), ErrorCode.MAKER_CHECKER_VIOLATION);
            assertError(() -> full().reject(CUSTOMER, "No", T1), ErrorCode.MAKER_CHECKER_VIOLATION);
        }

        @Test
        void approveBeforeVerifyIsInvalid() {
            assertError(() -> full().approve(OFFICER_B, T1), ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        void verifyTwiceIsInvalid() {
            assertError(() -> fullVerified().verify(OFFICER_B, T2), ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        void autoApproveIsInvalidForFull() {
            assertError(() -> full().autoApproveMin(T1), ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        void rejectFromSubmittedRecordsOfficerAndStrippedReason() {
            KycCase kycCase = full();

            kycCase.reject(OFFICER_A, "  Document unreadable ", T1);

            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.REJECTED);
            assertThat(kycCase.getRejectedBy()).isEqualTo(OFFICER_A);
            assertThat(kycCase.getRejectedAt()).isEqualTo(T1);
            assertThat(kycCase.getRejectionReason()).isEqualTo("Document unreadable");
        }

        @Test
        void rejectFromVerifiedByEitherOfficer() {
            KycCase byMaker = fullVerified();
            byMaker.reject(OFFICER_A, "Second look: mismatch", T2);
            assertThat(byMaker.getStatus()).isEqualTo(KycStatus.REJECTED);

            KycCase byChecker = fullVerified();
            byChecker.reject(OFFICER_B, "Address mismatch", T2);
            assertThat(byChecker.getRejectedBy()).isEqualTo(OFFICER_B);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        void rejectNeedsAReason(String reason) {
            KycCase kycCase = full();

            assertError(() -> kycCase.reject(OFFICER_A, reason, T1), ErrorCode.VALIDATION_FAILED);
            assertThat(kycCase.getStatus()).isEqualTo(KycStatus.SUBMITTED);
        }

        @Test
        void overlongReasonIsRejected() {
            assertError(() -> full().reject(OFFICER_A, "x".repeat(501), T1), ErrorCode.VALIDATION_FAILED);
        }

        @Test
        void finalStatesAllowNoFurtherMoves() {
            KycCase approved = fullVerified();
            approved.approve(OFFICER_B, T2);
            KycCase rejected = full();
            rejected.reject(OFFICER_A, "No", T1);

            for (KycCase kycCase : new KycCase[] {approved, rejected}) {
                assertThat(kycCase.getStatus().isFinal()).isTrue();
                assertError(() -> kycCase.verify(OFFICER_A, T2), ErrorCode.INVALID_STATE_TRANSITION);
                assertError(() -> kycCase.approve(OFFICER_B, T2), ErrorCode.INVALID_STATE_TRANSITION);
                assertError(() -> kycCase.reject(OFFICER_B, "Again", T2), ErrorCode.INVALID_STATE_TRANSITION);
            }
        }

        @Test
        void officerIdIsRequired() {
            Consumer<ThrowingCallable> npe = call -> assertThatThrownBy(call).isInstanceOf(NullPointerException.class);
            npe.accept(() -> full().verify(null, T1));
            npe.accept(() -> fullVerified().approve(null, T2));
            npe.accept(() -> full().reject(null, "No", T1));
        }
    }

    @Test
    void statusFinality() {
        assertThat(KycStatus.SUBMITTED.isFinal()).isFalse();
        assertThat(KycStatus.VERIFIED.isFinal()).isFalse();
        assertThat(KycStatus.APPROVED.isFinal()).isTrue();
        assertThat(KycStatus.REJECTED.isFinal()).isTrue();
    }
}
