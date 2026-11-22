-- FR-PR-2 data export (M10, Day 21): "every transfer this wallet sent or received". The sender side already has
-- ix_transfers_sender_created (V11); this index serves the receiver side of the OR, so neither half scans the table.
-- It also serves the transaction history (FR-S-1, M11), which lists incoming transfers too.
CREATE INDEX ix_transfers_receiver_created ON payments.transfers (receiver_wallet_id, created_at);
