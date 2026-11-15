package com.trustledger.core.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.ledger.domain.JournalEntry;
import com.trustledger.core.ledger.domain.JournalEntryRepository;
import com.trustledger.core.ledger.domain.LedgerAccount;
import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LedgerReversalServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    @Mock
    JournalEntryRepository entries;

    @Mock
    LedgerAccountRepository accounts;

    @Mock
    LedgerPostingService posting;

    LedgerReversalService service;

    private final LedgerAccount clearing = saved(LedgerAccount.open("PLATFORM:GATEWAY_CLEARING", AccountType.ASSET,
            Money.INR, NOW));
    private final LedgerAccount wallet = saved(LedgerAccount.open("WALLET:w", AccountType.LIABILITY, Money.INR,
            NOW));

    @BeforeEach
    void setUp() {
        service = new LedgerReversalService(entries, accounts, posting);
    }

    private static <T> T saved(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }

    private JournalEntry postedTopup() {
        JournalEntry entry = JournalEntry.start(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), null, null, NOW);
        entry.addLine(clearing, Direction.DEBIT, 150_050);
        entry.addLine(wallet, Direction.CREDIT, 150_050);
        return saved(entry);
    }

    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    @Test
    void postsEveryLineOnTheOtherSide() {
        JournalEntry original = postedTopup();
        UUID reversalId = UUID.randomUUID();
        when(entries.findById(original.getId())).thenReturn(Optional.of(original));
        when(accounts.findAllByIdIn(any())).thenReturn(List.of(wallet, clearing));
        when(posting.post(any())).thenReturn(reversalId);

        assertThat(service.reverse(original.getId(), "  Captured twice  ")).isEqualTo(reversalId);

        ArgumentCaptor<PostingCommand> sent = ArgumentCaptor.forClass(PostingCommand.class);
        verify(posting).post(sent.capture());
        PostingCommand command = sent.getValue();
        assertThat(command.entryType()).isEqualTo(EntryType.REVERSAL);
        assertThat(command.reversesEntryId()).isEqualTo(original.getId());
        assertThat(command.referenceId()).isEqualTo(original.getId());
        assertThat(command.reason()).isEqualTo("Captured twice");
        Money amount = Money.of(150_050, Money.INR);
        assertThat(command.lines()).containsExactly(
                PostingLine.credit("PLATFORM:GATEWAY_CLEARING", amount),
                PostingLine.debit("WALLET:w", amount));
    }

    @Test
    void unknownEntryIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(entries.findById(unknown)).thenReturn(Optional.empty());

        assertCode(() -> service.reverse(unknown, "why"), ErrorCode.NOT_FOUND);
        verify(posting, never()).post(any());
    }

    @Test
    void reversalCannotBeReversed() {
        UUID original = UUID.randomUUID();
        JournalEntry reversal = saved(JournalEntry.start(EntryType.REVERSAL, "JOURNAL_ENTRY", original, original,
                "why", NOW));
        when(entries.findById(reversal.getId())).thenReturn(Optional.of(reversal));

        assertCode(() -> service.reverse(reversal.getId(), "why"), ErrorCode.INVALID_STATE_TRANSITION);
        verify(posting, never()).post(any());
    }

    @Test
    void alreadyReversedIsADuplicate() {
        JournalEntry original = postedTopup();
        when(entries.findById(original.getId())).thenReturn(Optional.of(original));
        when(entries.existsByReversesEntryId(original.getId())).thenReturn(true);

        assertCode(() -> service.reverse(original.getId(), "why"), ErrorCode.DUPLICATE_POSTING);
        verify(posting, never()).post(any());
    }

    @Test
    void lineOnAMissingAccountIsABug() {
        JournalEntry original = postedTopup();
        when(entries.findById(original.getId())).thenReturn(Optional.of(original));
        when(accounts.findAllByIdIn(any())).thenReturn(List.of(clearing));

        assertThatThrownBy(() -> service.reverse(original.getId(), "why")).isInstanceOf(IllegalStateException.class);
        verify(posting, never()).post(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void reasonIsRequired(String reason) {
        assertCode(() -> service.reverse(UUID.randomUUID(), reason), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void reasonHasAMaximumLength() {
        assertCode(() -> service.reverse(UUID.randomUUID(), "x".repeat(501)), ErrorCode.VALIDATION_FAILED);
        assertThat(LedgerReversalService.requireReason("x".repeat(500))).hasSize(500);
    }
}
