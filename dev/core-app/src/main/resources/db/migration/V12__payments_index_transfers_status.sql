-- Payments module: the compliance review queue (FR-P-3, GET /compliance/transfers?status=HELD_FOR_REVIEW).
-- The queue filters by status and reads oldest first (created_at, then id as a tie-breaker), so this index
-- serves both the filter and the sort without a separate sort step.
CREATE INDEX ix_transfers_status_created ON payments.transfers (status, created_at, id);
