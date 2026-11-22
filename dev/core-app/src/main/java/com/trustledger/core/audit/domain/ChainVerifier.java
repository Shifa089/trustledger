package com.trustledger.core.audit.domain;

import com.trustledger.core.audit.domain.ChainVerification.BreakReason;
import com.trustledger.core.audit.domain.ChainVerification.Status;
import java.util.Objects;

/**
 * Walks the hash chain one row at a time, in {@code seq} order (FR-A-2, ADR 0008). Fed in batches, so the whole
 * chain never has to be in memory. For each row:
 * <ol>
 *   <li>its {@code prev_hash} must be the previous row's stored hash (the genesis hash for the first row);</li>
 *   <li>its content, rebuilt from the stored columns, must hash to its stored {@code hash}.</li>
 * </ol>
 * It stops at the first failure and reports that row's {@code seq}. A changed row fails check 2 at that row; a
 * changed {@code prev_hash} or a deleted row fails check 1 at the next row. Removing rows at the end of the chain
 * leaves a valid shorter chain: only an anchor kept outside the database ({@link ChainVerification#lastHash()})
 * can show that (ADR 0008). Not thread-safe; one instance per walk.
 */
public final class ChainVerifier {

    private String expectedPrevHash = AuditHashing.GENESIS_HASH;
    private long checked;
    private Long lastSeq;
    private Long brokenSeq;
    private BreakReason reason;

    /**
     * Checks the next row.
     *
     * @return {@code true} while the chain is intact; {@code false} once a break was found (further rows are
     *         ignored)
     */
    public boolean accept(StoredAuditEvent row) {
        Objects.requireNonNull(row, "row");
        if (brokenSeq != null) {
            return false;
        }
        if (!expectedPrevHash.equals(row.prevHash())) {
            return broken(row, BreakReason.PREV_HASH_MISMATCH);
        }
        String recomputed;
        try {
            recomputed = AuditHashing.hash(row.prevHash(), row.toEvent());
        } catch (IllegalArgumentException e) {
            return broken(row, BreakReason.UNREADABLE);
        }
        if (!recomputed.equals(row.hash())) {
            return broken(row, BreakReason.HASH_MISMATCH);
        }
        checked++;
        lastSeq = row.seq();
        expectedPrevHash = row.hash();
        return true;
    }

    public boolean isBroken() {
        return brokenSeq != null;
    }

    public ChainVerification result() {
        String lastHash = lastSeq == null ? null : expectedPrevHash;
        if (brokenSeq != null) {
            return new ChainVerification(Status.BROKEN, checked, lastSeq, lastHash, brokenSeq, reason);
        }
        return new ChainVerification(Status.OK, checked, lastSeq, lastHash, null, null);
    }

    private boolean broken(StoredAuditEvent row, BreakReason why) {
        brokenSeq = row.seq();
        reason = why;
        return false;
    }
}
