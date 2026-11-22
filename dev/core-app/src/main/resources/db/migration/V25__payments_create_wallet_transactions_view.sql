-- Payments module: a wallet's transaction history (FR-S-1, M11, GET /wallets/me/transactions) as one read-only view
-- over transfers and top-ups, so the reporting module can filter, sort and page it with one query through PaymentsApi.
-- One row per (wallet, transfer or top-up):
--   TRANSFER_OUT  the sender's row, in every status (held, rejected and failed ones too: the sender asked for them);
--   TRANSFER_IN   the receiver's row; visible only once money arrived (COMPLETED, or REVERSED later): a receiver never
--                 sees a held or rejected transfer meant for them;
--   TOPUP         every top-up; visible except CREATED (no gateway order yet, nothing the customer could act on).
-- source_id is the transfer or top-up ID; (wallet_id, source_id) is unique because a transfer's sender and receiver
-- differ.
--
-- Why "visible" is a column and not a WHERE on each branch (measured, docs/perf.md): PostgreSQL flattens a UNION ALL
-- view into one Append only when no branch has its own WHERE. A branch with one stays a separate subquery, is planned
-- without the outer ORDER BY ... LIMIT, and sorts all the wallet's rows of that branch. Without branch WHEREs, each
-- branch reads its index on (wallet, created_at, id) (V26) backwards, PostgreSQL merges them in order (Merge Append)
-- and stops after one page. The history query therefore ALWAYS adds "visible" (WalletTransactionSpecs.matching).
-- Casts pin the column types for Hibernate's schema validation.

CREATE VIEW payments.wallet_transactions AS
SELECT t.sender_wallet_id                   AS wallet_id,
       t.id                                 AS source_id,
       CAST('TRANSFER_OUT' AS varchar(20))  AS type,
       t.status                             AS status,
       t.amount_minor                       AS amount_minor,
       t.currency                           AS currency,
       t.receiver_wallet_id                 AS counterparty_wallet_id,
       t.note                               AS note,
       t.created_at                         AS created_at,
       true                                 AS visible
FROM payments.transfers t
UNION ALL
SELECT t.receiver_wallet_id,
       t.id,
       CAST('TRANSFER_IN' AS varchar(20)),
       t.status,
       t.amount_minor,
       t.currency,
       t.sender_wallet_id,
       t.note,
       t.created_at,
       t.status IN ('COMPLETED', 'REVERSED')
FROM payments.transfers t
UNION ALL
SELECT p.wallet_id,
       p.id,
       CAST('TOPUP' AS varchar(20)),
       p.status,
       p.amount_minor,
       p.currency,
       CAST(NULL AS uuid),
       CAST(NULL AS varchar(140)),
       p.created_at,
       p.status <> 'CREATED'
FROM payments.topups p;
