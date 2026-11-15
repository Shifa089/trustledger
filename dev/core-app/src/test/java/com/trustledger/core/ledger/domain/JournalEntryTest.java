package com.trustledger.core.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class JournalEntryTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID REF = UUID.randomUUID();

    /** An account as loaded from the database (with an ID). */
    static LedgerAccount savedAccount(String code, AccountType type) {
        LedgerAccount account = LedgerAccount.open(code, type, Money.INR, NOW);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    private static JournalEntry topup() {
        return JournalEntry.start(EntryType.TOPUP, "TOPUP", REF, null, null, NOW);
    }

    @Test
    void startsEmptyWithItsReference() {
        JournalEntry entry = topup();

        assertThat(entry.getEntryType()).isEqualTo(EntryType.TOPUP);
        assertThat(entry.getReferenceType()).isEqualTo("TOPUP");
        assertThat(entry.getReferenceId()).isEqualTo(REF);
        assertThat(entry.getReversesEntryId()).isNull();
        assertThat(entry.reason()).isNull();
        assertThat(entry.getPostedAt()).isEqualTo(NOW);
        assertThat(entry.getCreatedAt()).isEqualTo(NOW);
        assertThat(entry.isReversal()).isFalse();
        assertThat(entry.lines()).isEmpty();
    }

    @Test
    void linesAreNumberedInOrder() {
        LedgerAccount clearing = savedAccount("PLATFORM:GATEWAY_CLEARING", AccountType.ASSET);
        LedgerAccount wallet = savedAccount("WALLET:a", AccountType.LIABILITY);
        JournalEntry entry = topup();

        entry.addLine(clearing, Direction.DEBIT, 150_050);
        entry.addLine(wallet, Direction.CREDIT, 150_050);

        assertThat(entry.lines()).extracting(JournalLine::getLineNo).containsExactly(1, 2);
        assertThat(entry.lines()).extracting(JournalLine::getAccountId)
                .containsExactly(clearing.getId(), wallet.getId());
        assertThat(entry.lines()).extracting(JournalLine::getDirection)
                .containsExactly(Direction.DEBIT, Direction.CREDIT);
        assertThat(entry.lines()).extracting(JournalLine::getAmountMinor).containsExactly(150_050L, 150_050L);
        assertThat(entry.lines()).extracting(JournalLine::getCreatedAt).containsOnly(NOW);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void lineAmountMustBePositive(long amount) {
        JournalEntry entry = topup();
        LedgerAccount account = savedAccount("WALLET:a", AccountType.LIABILITY);

        assertThatIllegalArgumentException().isThrownBy(() -> entry.addLine(account, Direction.DEBIT, amount));
        assertThat(entry.lines()).isEmpty();
    }

    @Test
    void lineNeedsASavedAccount() {
        JournalEntry entry = topup();
        LedgerAccount unsaved = LedgerAccount.open("WALLET:a", AccountType.LIABILITY, Money.INR, NOW);

        assertThatNullPointerException().isThrownBy(() -> entry.addLine(unsaved, Direction.DEBIT, 1));
        assertThatNullPointerException().isThrownBy(() -> entry.addLine(null, Direction.DEBIT, 1));
    }

    @Test
    void postedEntryCannotGainLines() {
        JournalEntry entry = topup();
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());

        assertThatIllegalStateException().isThrownBy(
                () -> entry.addLine(savedAccount("WALLET:a", AccountType.LIABILITY), Direction.DEBIT, 1));
    }

    @Test
    void linesAreReadOnly() {
        JournalEntry entry = topup();

        assertThatThrownBy(() -> entry.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reversalPointsToTheOriginalAndSaysWhy() {
        UUID original = UUID.randomUUID();

        JournalEntry entry = JournalEntry.start(EntryType.REVERSAL, "JOURNAL_ENTRY", original, original,
                "Captured twice", NOW);

        assertThat(entry.isReversal()).isTrue();
        assertThat(entry.getReversesEntryId()).isEqualTo(original);
        assertThat(entry.reason()).isEqualTo("Captured twice");
    }

    @Test
    void reversalFieldsOnlyOnAReversal() {
        UUID other = UUID.randomUUID();

        assertThatIllegalArgumentException().isThrownBy(
                () -> JournalEntry.start(EntryType.TOPUP, "TOPUP", REF, other, null, NOW));
        assertThatIllegalArgumentException().isThrownBy(
                () -> JournalEntry.start(EntryType.TOPUP, "TOPUP", REF, null, "why", NOW));
        assertThatIllegalArgumentException().isThrownBy(
                () -> JournalEntry.start(EntryType.REVERSAL, "JOURNAL_ENTRY", REF, null, "why", NOW));
        assertThatIllegalArgumentException().isThrownBy(
                () -> JournalEntry.start(EntryType.REVERSAL, "JOURNAL_ENTRY", REF, other, null, NOW));
    }

    @Test
    void requiredFields() {
        assertThatNullPointerException().isThrownBy(() -> JournalEntry.start(null, "TOPUP", REF, null, null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> JournalEntry.start(EntryType.TOPUP, null, REF, null, null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> JournalEntry.start(EntryType.TOPUP, "TOPUP", null, null, null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> JournalEntry.start(EntryType.TOPUP, "TOPUP", REF, null, null, null));
    }
}
