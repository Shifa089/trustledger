package com.trustledger.core.payments;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs tasks so that they really collide: every task is submitted first and blocks on one start
 * {@link CountDownLatch}; only then is the latch opened. Without it the pool would start threads one by one and
 * the first tasks could finish before the last ones began.
 * <p>
 * The whole run must finish within {@link #TIMEOUT}. A run that doesn't is reported as a probable deadlock or an
 * unbounded lock wait, rather than hanging the build. An exception a task didn't handle fails the run.
 */
final class ConcurrentRun {

    static final Duration TIMEOUT = Duration.ofSeconds(60);
    static final int THREADS = 16;

    private ConcurrentRun() {
    }

    static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                try {
                    results.add(future.get(remaining, TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    throw new AssertionError("Tasks did not finish within " + TIMEOUT.toSeconds()
                            + " s: probable deadlock or unbounded lock wait", e);
                } catch (ExecutionException e) {
                    throw new AssertionError("A task failed unexpectedly: " + e.getCause(), e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
