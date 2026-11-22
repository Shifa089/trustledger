-- Payments module: indexes for the transaction history (FR-S-1, M11; EXPLAIN ANALYZE in docs/perf.md).
-- The history reads newest first with the row ID as a tie-breaker (ORDER BY created_at DESC, source_id DESC) and,
-- in cursor mode, seeks to (created_at, id) < (cursor). Adding id as the last column lets each branch of
-- payments.wallet_transactions return its rows already in that order (a backward index scan), so the three
-- branches are merged without a sort and the scan stops after one page.
-- The new indexes have the old ones' prefix, so they also serve the limit sums (wallet + created_at window) and
-- the data export; the old ones are dropped to keep one index per access path.

CREATE INDEX ix_transfers_sender_created_id ON payments.transfers (sender_wallet_id, created_at, id);
DROP INDEX payments.ix_transfers_sender_created;

CREATE INDEX ix_transfers_receiver_created_id ON payments.transfers (receiver_wallet_id, created_at, id);
DROP INDEX payments.ix_transfers_receiver_created;

CREATE INDEX ix_topups_wallet_created_id ON payments.topups (wallet_id, created_at, id);
DROP INDEX payments.ix_topups_wallet_created;
