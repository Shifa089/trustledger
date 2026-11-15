package com.trustledger.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostingCommandTest {

    private static final UUID REF = UUID.randomUUID();
    private static final Money TEN = Money.of(1_000, Money.INR);
    private static final List<PostingLine> LINES = List.of(
            PostingLine.debit(AccountCodes.GATEWAY_CLEARING, TEN),
            PostingLine.credit(AccountCodes.wallet(UUID.randomUUID()), TEN));

    @Test
    void businessPostingHasNoReversalFields() {
        PostingCommand command = PostingCommand.of(EntryType.TOPUP, "TOPUP", REF, LINES);

        assertThat(command.reversesEntryId()).isNull();
        assertThat(command.reason()).isNull();
        assertThat(command.isReversal()).isFalse();
        assertThat(command.lines()).isEqualTo(LINES);
    }

    @Test
    void reversalIsReferencedByTheOriginalEntry() {
        UUID original = UUID.randomUUID();

        PostingCommand command = PostingCommand.reversal(original, "Captured twice", LINES);

        assertThat(command.entryType()).isEqualTo(EntryType.REVERSAL);
        assertThat(command.referenceType()).isEqualTo(PostingCommand.REVERSAL_REFERENCE_TYPE);
        assertThat(command.referenceId()).isEqualTo(original);
        assertThat(command.reversesEntryId()).isEqualTo(original);
        assertThat(command.isReversal()).isTrue();
    }

    @Test
    void linesAreCopiedAndReadOnly() {
        List<PostingLine> mutable = new ArrayList<>(LINES);
        PostingCommand command = PostingCommand.of(EntryType.TOPUP, "TOPUP", REF, mutable);
        mutable.clear();

        assertThat(command.lines()).hasSize(2);
        assertThatThrownBy(() -> command.lines().add(LINES.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anEmptyListIsAShapeTheLedgerRejectsNotTheRecord() {
        assertThat(PostingCommand.of(EntryType.TOPUP, "TOPUP", REF, List.of()).lines()).isEmpty();
    }

    @Test
    void requiredFields() {
        assertThatNullPointerException().isThrownBy(() -> PostingCommand.of(null, "TOPUP", REF, LINES));
        assertThatNullPointerException().isThrownBy(() -> PostingCommand.of(EntryType.TOPUP, null, REF, LINES));
        assertThatNullPointerException().isThrownBy(() -> PostingCommand.of(EntryType.TOPUP, "TOPUP", null, LINES));
        assertThatNullPointerException().isThrownBy(() -> PostingCommand.of(EntryType.TOPUP, "TOPUP", REF, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "topup", "TOP-UP", "TOPUP1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void referenceTypeIsUpperCaseWord(String referenceType) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingCommand.of(EntryType.TOPUP, referenceType, REF, LINES));
    }

    @Test
    void reversalFieldsOnlyOnAReversal() {
        UUID other = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(
                () -> new PostingCommand(EntryType.TOPUP, "TOPUP", REF, LINES, other, null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new PostingCommand(EntryType.TOPUP, "TOPUP", REF, LINES, null, "why"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new PostingCommand(EntryType.REVERSAL, "JOURNAL_ENTRY", REF, LINES, null, "why"));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new PostingCommand(EntryType.REVERSAL, "JOURNAL_ENTRY", REF, LINES, other, null));
    }
}
