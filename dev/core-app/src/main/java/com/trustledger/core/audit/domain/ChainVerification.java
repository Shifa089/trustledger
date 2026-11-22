package com.trustledger.core.audit.domain;

/**
 * The result of walking the hash chain (FR-A-2).
 *
 * @param checkedEvents rows found intact, in {@code seq} order, before the break (all of them when OK)
 * @param lastSeq       the last intact row; {@code null} for an empty chain or a break at the first row
 * @param lastHash      its hash, the value to anchor outside the database; {@code null} with {@code lastSeq}
 * @param brokenSeq     the first row that fails a check; {@code null} when OK
 * @param reason        why it fails; {@code null} when OK
 */
public record ChainVerification(Status status, long checkedEvents, Long lastSeq, String lastHash, Long brokenSeq,
        BreakReason reason) {

    public enum Status {
        OK,
        BROKEN
    }

    public enum BreakReason {
        /** The row's {@code prev_hash} isn't the previous row's hash: a row before it was changed or deleted. */
        PREV_HASH_MISMATCH,
        /** The row's stored hash isn't the hash of its stored content: this row was changed. */
        HASH_MISMATCH,
        /** The row's content can't be an audit event any more (for example a state that isn't an object). */
        UNREADABLE
    }
}
