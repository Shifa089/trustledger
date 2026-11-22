package com.trustledger.aml.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.aml.shared.ErrorCode;
import com.trustledger.aml.shared.TrustLedgerException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/** FR-AML-4: OPEN → INVESTIGATING → CLOSED_FALSE_POSITIVE or ESCALATED; every other step is refused. */
class AmlCaseWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(600);
    private static final UUID WALLET = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();
    private static final UUID OTHER_OFFICER = UUID.randomUUID();

    // --- assign --------------------------------------------------------------------------------------------------

    @Test
    void assigningAnOpenCaseStartsTheInvestigation() {
        AmlCase amlCase = AmlCase.open(WALLET, NOW);

        amlCase.assign(OFFICER, LATER);

        assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThat(amlCase.getAssignedTo()).isEqualTo(OFFICER);
        assertThat(amlCase.getUpdatedAt()).isEqualTo(LATER);
        assertThat(amlCase.isActive()).isTrue();
    }

    @Test
    void anInvestigatingCaseCanBeReassigned() {
        AmlCase amlCase = investigating();

        amlCase.assign(OTHER_OFFICER, LATER);

        assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThat(amlCase.getAssignedTo()).isEqualTo(OTHER_OFFICER);
    }

    @ParameterizedTest
    @EnumSource(value = CaseStatus.class, names = {"CLOSED_FALSE_POSITIVE", "ESCALATED"})
    void aFinishedCaseCantBeAssigned(CaseStatus finished) {
        AmlCase amlCase = inStatus(finished);

        assertRefused(() -> amlCase.assign(OFFICER, LATER), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(amlCase.getStatus()).isEqualTo(finished);
    }

    // --- close and escalate ----------------------------------------------------------------------------------------

    @Test
    void theAssigneeClosesAFalsePositiveWithNotes() {
        AmlCase amlCase = investigating();

        amlCase.closeAsFalsePositive(OFFICER, "  Salary payments, checked with payslips.  ", LATER);

        assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.CLOSED_FALSE_POSITIVE);
        assertThat(amlCase.getResolution()).isEqualTo("Salary payments, checked with payslips.");
        assertThat(amlCase.getUpdatedAt()).isEqualTo(LATER);
        assertThat(amlCase.isFinished()).isTrue();
    }

    @Test
    void theAssigneeEscalatesWithNotes() {
        AmlCase amlCase = investigating();

        amlCase.escalate(OFFICER, "Structuring pattern confirmed; STR filed.", LATER);

        assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.ESCALATED);
        assertThat(amlCase.getResolution()).isEqualTo("Structuring pattern confirmed; STR filed.");
        assertThat(amlCase.isActive()).isFalse();
    }

    @Test
    void anOpenCaseMustBeInvestigatedBeforeItIsFinished() {
        AmlCase amlCase = AmlCase.open(WALLET, NOW);

        assertRefused(() -> amlCase.closeAsFalsePositive(OFFICER, "notes", LATER),
                ErrorCode.INVALID_STATE_TRANSITION);
        assertRefused(() -> amlCase.escalate(OFFICER, "notes", LATER), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.OPEN);
    }

    @ParameterizedTest
    @EnumSource(value = CaseStatus.class, names = {"CLOSED_FALSE_POSITIVE", "ESCALATED"})
    void aFinishedCaseCantBeFinishedAgain(CaseStatus finished) {
        AmlCase amlCase = inStatus(finished);

        assertRefused(() -> amlCase.closeAsFalsePositive(OFFICER, "notes", LATER),
                ErrorCode.INVALID_STATE_TRANSITION);
        assertRefused(() -> amlCase.escalate(OFFICER, "notes", LATER), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(amlCase.getResolution()).isEqualTo("earlier notes");
    }

    @Test
    void onlyTheAssigneeFinishesTheCase() {
        AmlCase amlCase = investigating();

        assertRefused(() -> amlCase.closeAsFalsePositive(OTHER_OFFICER, "notes", LATER),
                ErrorCode.NOT_CASE_ASSIGNEE);
        assertRefused(() -> amlCase.escalate(OTHER_OFFICER, "notes", LATER), ErrorCode.NOT_CASE_ASSIGNEE);
        assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThat(amlCase.getResolution()).isNull();
    }

    @Test
    void notesAreRequiredAndBounded() {
        String tooLong = "x".repeat(AmlCase.MAX_NOTES_LENGTH + 1);
        for (String bad : new String[] {null, "", "   ", tooLong}) {
            AmlCase amlCase = investigating();
            assertRefused(() -> amlCase.escalate(OFFICER, bad, LATER), ErrorCode.VALIDATION_FAILED);
            assertRefused(() -> amlCase.closeAsFalsePositive(OFFICER, bad, LATER), ErrorCode.VALIDATION_FAILED);
            assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        }
        AmlCase amlCase = investigating();
        amlCase.escalate(OFFICER, "x".repeat(AmlCase.MAX_NOTES_LENGTH), LATER);
        assertThat(amlCase.getResolution()).hasSize(AmlCase.MAX_NOTES_LENGTH);
    }

    @Test
    void theErrorMessageNamesTheStatusButNeverTheNotes() {
        AmlCase amlCase = investigating();
        amlCase.escalate(OFFICER, "Customer PAN ABCDE1234F", LATER);

        assertThatThrownBy(() -> amlCase.escalate(OFFICER, "Customer PAN ABCDE1234F", LATER))
                .hasMessageContaining("ESCALATED")
                .hasMessageNotContaining("ABCDE1234F");
    }

    private static AmlCase investigating() {
        AmlCase amlCase = AmlCase.open(WALLET, NOW);
        amlCase.assign(OFFICER, NOW);
        return amlCase;
    }

    private static AmlCase inStatus(CaseStatus status) {
        AmlCase amlCase = investigating();
        ReflectionTestUtils.setField(amlCase, "status", status);
        ReflectionTestUtils.setField(amlCase, "resolution", "earlier notes");
        return amlCase;
    }

    private static void assertRefused(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        (Consumer<TrustLedgerException>) e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
