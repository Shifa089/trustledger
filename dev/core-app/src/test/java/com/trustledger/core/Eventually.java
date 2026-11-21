package com.trustledger.core;

import java.time.Duration;

/**
 * Retries an assertion until it passes or a timeout runs out. Needed since M8: with the event publication registry,
 * Spring Modulith turns on {@code @EnableAsync}, so {@code @ApplicationModuleListener}s run on another thread after
 * the commit. (A tiny helper instead of adding Awaitility as a dependency.)
 */
public final class Eventually {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 50;

    private Eventually() {
    }

    public static void eventually(Runnable assertion) {
        eventually(DEFAULT_TIMEOUT, assertion);
    }

    public static void eventually(Duration timeout, Runnable assertion) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }
}
