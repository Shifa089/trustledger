-- Ledger module (SPEC §4, FR-L-1 to FR-L-3): journal entries and their lines.
-- Every money movement is one entry with at least two lines whose debits equal its credits. Entries and
-- lines are append-only; a mistake is corrected by a REVERSAL entry that points to the entry it reverses.

CREATE TABLE ledger.journal_entries (
    id                uuid         PRIMARY KEY,
    entry_type        varchar(16)  NOT NULL,
    -- The business event behind the entry, e.g. ('TOPUP', <topup id>) or ('JOURNAL_ENTRY', <reversed id>).
    reference_type    varchar(32)  NOT NULL,
    reference_id      uuid         NOT NULL,
    reverses_entry_id uuid,
    -- Why a reversal was posted. Free text: never logged.
    reason            varchar(500),
    posted_at         timestamptz  NOT NULL,
    created_at        timestamptz  NOT NULL,
    -- The same business event can't be posted twice (FR-L-1, idempotent posting).
    CONSTRAINT uk_journal_entries_reference UNIQUE (reference_type, reference_id, entry_type),
    -- An entry is reversed at most once (NULLs don't collide in a unique constraint).
    CONSTRAINT uk_journal_entries_reverses UNIQUE (reverses_entry_id),
    CONSTRAINT fk_journal_entries_reverses FOREIGN KEY (reverses_entry_id) REFERENCES ledger.journal_entries (id),
    CONSTRAINT ck_journal_entries_type CHECK (entry_type IN ('TOPUP', 'P2P_TRANSFER', 'REVERSAL')),
    CONSTRAINT ck_journal_entries_reference_type CHECK (reference_type ~ '^[A-Z_]+$'),
    -- FR-L-3: a REVERSAL, and only a REVERSAL, points to another entry and says why.
    CONSTRAINT ck_journal_entries_reversal CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    CONSTRAINT ck_journal_entries_reason
        CHECK (((entry_type = 'REVERSAL') = (reason IS NOT NULL)) AND (reason IS NULL OR btrim(reason) <> '')),
    CONSTRAINT ck_journal_entries_not_self CHECK (reverses_entry_id <> id)
);

CREATE TABLE ledger.journal_lines (
    id           uuid         PRIMARY KEY,
    entry_id     uuid         NOT NULL,
    account_id   uuid         NOT NULL,
    direction    varchar(6)   NOT NULL,
    amount_minor bigint       NOT NULL,
    line_no      integer      NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT fk_journal_lines_entry FOREIGN KEY (entry_id) REFERENCES ledger.journal_entries (id),
    CONSTRAINT fk_journal_lines_account FOREIGN KEY (account_id) REFERENCES ledger.ledger_accounts (id),
    CONSTRAINT uk_journal_lines_entry_line UNIQUE (entry_id, line_no),
    CONSTRAINT ck_journal_lines_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- The direction carries the sign; an amount is always positive.
    CONSTRAINT ck_journal_lines_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_journal_lines_line_no CHECK (line_no >= 1)
);

-- Balance of one account, and the reconciliation job (M5 part 2), read lines by account.
CREATE INDEX ix_journal_lines_account_entry ON ledger.journal_lines (account_id, entry_id);

-- FR-L-2: append-only. The database refuses UPDATE, DELETE and TRUNCATE, even from raw SQL.
CREATE FUNCTION ledger.reject_journal_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger.% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_journal_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger.journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_journal_mutation();

CREATE TRIGGER trg_journal_entries_no_truncate
    BEFORE TRUNCATE ON ledger.journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_journal_mutation();

CREATE TRIGGER trg_journal_lines_append_only
    BEFORE UPDATE OR DELETE ON ledger.journal_lines
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_journal_mutation();

CREATE TRIGGER trg_journal_lines_no_truncate
    BEFORE TRUNCATE ON ledger.journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_journal_mutation();

-- FR-L-1 in the database: at COMMIT, every entry touched by the transaction has at least 2 lines, its debits
-- equal its credits, and all its accounts share one currency. Deferred, because an entry and its lines are
-- inserted one row at a time and are only complete at the end of the transaction. Fires on entries too, so
-- an entry with no lines at all is caught.
CREATE FUNCTION ledger.check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_entry_id   uuid;
    v_lines      bigint;
    v_debits     numeric;
    v_credits    numeric;
    v_currencies bigint;
BEGIN
    -- Separate statements: NEW has no entry_id on journal_entries, and plpgsql only plans the branch it runs.
    IF TG_TABLE_NAME = 'journal_entries' THEN
        v_entry_id := NEW.id;
    ELSE
        v_entry_id := NEW.entry_id;
    END IF;

    SELECT count(*),
           coalesce(sum(l.amount_minor) FILTER (WHERE l.direction = 'DEBIT'), 0),
           coalesce(sum(l.amount_minor) FILTER (WHERE l.direction = 'CREDIT'), 0),
           count(DISTINCT a.currency)
      INTO v_lines, v_debits, v_credits, v_currencies
      FROM ledger.journal_lines l
      JOIN ledger.ledger_accounts a ON a.id = l.account_id
     WHERE l.entry_id = v_entry_id;

    IF v_lines < 2 THEN
        RAISE EXCEPTION 'journal entry % has % line(s); at least 2 are required', v_entry_id, v_lines
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_debits <> v_credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits % <> credits %', v_entry_id, v_debits, v_credits
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_currencies <> 1 THEN
        RAISE EXCEPTION 'journal entry % mixes % currencies', v_entry_id, v_currencies
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_journal_entries_balanced
    AFTER INSERT ON ledger.journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

CREATE CONSTRAINT TRIGGER trg_journal_lines_balanced
    AFTER INSERT ON ledger.journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();
