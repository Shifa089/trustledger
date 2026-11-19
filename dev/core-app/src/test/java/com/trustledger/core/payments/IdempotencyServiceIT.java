package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.application.IdempotencyClaim;
import com.trustledger.core.payments.application.IdempotencyCleanupJob;
import com.trustledger.core.payments.application.IdempotencyService;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link IdempotencyService} and the cleanup job against the real table and its primary key. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyServiceIT {

    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Autowired
    IdempotencyService service;

    @Autowired
    IdempotencyCleanupJob cleanup;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LockProvider lockProvider;

    @Autowired
    Clock clock;

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    private String status(UUID user, UUID key) {
        List<String> rows = jdbc.queryForList(
                "select status from payments.idempotency_keys where user_id = ? and idem_key = ?", String.class,
                user, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void expire(UUID user, UUID key) {
        jdbc.update("""
                update payments.idempotency_keys set created_at = now() - interval '25 hours',
                    expires_at = now() - interval '1 hour' where user_id = ? and idem_key = ?""", user, key);
    }

    @Test
    void newKeyIsClaimedInProgressFor24Hours() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        assertThat(service.claim(user, key, HASH)).isInstanceOf(IdempotencyClaim.Claimed.class);

        assertThat(status(user, key)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("""
                select extract(epoch from expires_at - created_at)::bigint from payments.idempotency_keys
                where user_id = ? and idem_key = ?""", Long.class, user, key))
                .isEqualTo(Duration.ofHours(24).toSeconds());
    }

    @Test
    void completedKeyReplaysTheStoredResponse() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        service.claim(user, key, HASH);

        assertThat(service.complete(user, key, 201, "{\"transferId\":\"t-1\",\"status\":\"COMPLETED\"}")).isTrue();

        IdempotencyClaim replay = service.claim(user, key, HASH);
        assertThat(replay).isInstanceOf(IdempotencyClaim.Replay.class);
        IdempotencyClaim.Replay stored = (IdempotencyClaim.Replay) replay;
        assertThat(stored.status()).isEqualTo(201);
        assertThat(stored.body()).contains("\"transferId\": \"t-1\"").contains("\"status\": \"COMPLETED\"");
    }

    @Test
    void emptyResponseBodyReplaysAsEmpty() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        service.claim(user, key, HASH);
        service.complete(user, key, 204, null);

        assertThat(service.claim(user, key, HASH)).isEqualTo(new IdempotencyClaim.Replay(204, null));
    }

    @Test
    void inProgressKeyIs409AndAnotherBodyIs422() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        service.claim(user, key, HASH);

        refused(() -> service.claim(user, key, HASH), ErrorCode.REQUEST_IN_PROGRESS);
        refused(() -> service.claim(user, key, OTHER_HASH), ErrorCode.IDEMPOTENCY_KEY_REUSED);

        service.complete(user, key, 201, "{}");
        refused(() -> service.claim(user, key, OTHER_HASH), ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void releasedKeyCanBeClaimedAgain() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        service.claim(user, key, HASH);

        assertThat(service.release(user, key)).isTrue();
        assertThat(status(user, key)).isNull();

        assertThat(service.claim(user, key, OTHER_HASH)).isInstanceOf(IdempotencyClaim.Claimed.class);
    }

    @Test
    void completedKeyIsNeverReleased() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        service.claim(user, key, HASH);
        service.complete(user, key, 201, "{}");

        assertThat(service.release(user, key)).isFalse();
        assertThat(service.complete(user, key, 200, "{}")).isFalse();
        assertThat(status(user, key)).isEqualTo("COMPLETED");
    }

    @Test
    void expiredKeyIsTakenOverEvenWithAnotherBody() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        service.claim(user, key, HASH);
        service.complete(user, key, 201, "{}");
        expire(user, key);

        assertThat(service.claim(user, key, OTHER_HASH)).isInstanceOf(IdempotencyClaim.Claimed.class);

        assertThat(status(user, key)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("""
                select response_body is null and response_status is null and expires_at > now()
                from payments.idempotency_keys where user_id = ? and idem_key = ?""", Boolean.class, user, key))
                .isTrue();
    }

    @Test
    void keysArePerUser() {
        UUID key = UUID.randomUUID();

        assertThat(service.claim(UUID.randomUUID(), key, HASH)).isInstanceOf(IdempotencyClaim.Claimed.class);
        assertThat(service.claim(UUID.randomUUID(), key, HASH)).isInstanceOf(IdempotencyClaim.Claimed.class);
    }

    @Test
    void twentyConcurrentClaimsOfOneKeyHaveExactlyOneWinner() throws Exception {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        int threads = 20;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return service.claim(user, key, HASH);
                    } catch (TrustLedgerException e) {
                        return e.errorCode();
                    }
                }));
            }
            ready.await();
            go.countDown();
        }

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> result : results) {
            outcomes.add(result.get());
        }
        assertThat(outcomes).filteredOn(o -> o instanceof IdempotencyClaim.Claimed).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == ErrorCode.REQUEST_IN_PROGRESS).hasSize(threads - 1);
    }

    // --- cleanup job ---

    @Test
    void cleanupDeletesExpiredKeysOnly() {
        UUID user = UUID.randomUUID();
        UUID expiredInProgress = UUID.randomUUID();
        UUID expiredCompleted = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        service.claim(user, expiredInProgress, HASH);
        service.claim(user, expiredCompleted, HASH);
        service.complete(user, expiredCompleted, 201, "{}");
        service.claim(user, live, HASH);
        expire(user, expiredInProgress);
        expire(user, expiredCompleted);

        assertThat(cleanup.runLocked()).hasValueSatisfying(deleted -> assertThat(deleted).isGreaterThanOrEqualTo(2));

        assertThat(status(user, expiredInProgress)).isNull();
        assertThat(status(user, expiredCompleted)).isNull();
        assertThat(status(user, live)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void cleanupSkipsWhileAnotherInstanceHoldsTheLock() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        service.claim(user, key, HASH);
        expire(user, key);

        SimpleLock held = lockProvider.lock(new LockConfiguration(clock.instant(), IdempotencyCleanupJob.LOCK_NAME,
                Duration.ofMinutes(5), Duration.ZERO)).orElseThrow();
        try {
            assertThat(cleanup.runLocked()).isEmpty();
            assertThat(status(user, key)).isEqualTo("IN_PROGRESS");
        } finally {
            held.unlock();
        }

        assertThat(cleanup.runLocked()).isPresent();
        assertThat(status(user, key)).isNull();
    }
}
