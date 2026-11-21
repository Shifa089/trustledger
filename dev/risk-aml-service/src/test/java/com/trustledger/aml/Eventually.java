package com.trustledger.aml;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls a condition that an asynchronous consumer makes true. */
public final class Eventually {

    private Eventually() {
    }

    public static void await(Duration timeout, String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout + " waiting for: " + description);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for: " + description, e);
            }
        }
    }
}
