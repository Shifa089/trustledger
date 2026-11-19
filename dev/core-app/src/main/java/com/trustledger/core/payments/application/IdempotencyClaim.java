package com.trustledger.core.payments.application;

/** Outcome of {@link IdempotencyService#claim}: run the request, or answer with the stored response. */
public sealed interface IdempotencyClaim {

    /** This request owns the key: run the handler, then {@code complete} or {@code release}. */
    record Claimed() implements IdempotencyClaim {
    }

    /**
     * The same request already completed: send this response again without running the handler.
     *
     * @param body the stored JSON, or {@code null} for an empty body
     */
    record Replay(int status, String body) implements IdempotencyClaim {
    }
}
