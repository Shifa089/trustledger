package com.trustledger.core.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Outbox housekeeping (M8, ADR 0005 part B): completed publications older than the retention (7 days) are deleted;
 * recent completed ones and every incomplete or failed one stay, whatever their age, because republishing needs
 * them. One instance at a time (ShedLock).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventPublicationCleanupJobIT {

    private static final String TEST_EVENT_TYPE = "com.trustledger.test.CleanupProbe";

    @Autowired
    EventPublicationCleanupJob job;

    @Autowired
    LockProvider lockProvider;

    @Autowired
    JdbcTemplate jdbc;

    private UUID insert(String status, Duration publishedAgo, Duration completedAgo) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into shared.event_publication
                    (id, listener_id, event_type, serialized_event, publication_date, completion_date, status,
                     completion_attempts)
                values (?, 'test.listener', ?, '{}', ?, ?, ?, 1)
                """, id, TEST_EVENT_TYPE, Timestamp.from(now.minus(publishedAgo)),
                completedAgo == null ? null : Timestamp.from(now.minus(completedAgo)), status);
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("select count(*) from shared.event_publication where id = ?", Long.class, id) == 1;
    }

    @Test
    void deletesOnlyCompletedPublicationsOlderThanTheRetention() {
        UUID oldCompleted = insert("COMPLETED", Duration.ofDays(9), Duration.ofDays(8));
        UUID recentCompleted = insert("COMPLETED", Duration.ofDays(6), Duration.ofDays(6));
        UUID oldFailed = insert("FAILED", Duration.ofDays(30), null);
        UUID oldPublished = insert("PUBLISHED", Duration.ofDays(30), null);

        assertThat(job.runLocked()).isTrue();

        assertThat(exists(oldCompleted)).as("completed 8 days ago").isFalse();
        assertThat(exists(recentCompleted)).as("completed 6 days ago").isTrue();
        assertThat(exists(oldFailed)).as("failed: republishing needs it").isTrue();
        assertThat(exists(oldPublished)).as("never completed: republishing needs it").isTrue();
    }

    @Test
    void skipsWhileAnotherInstanceHoldsTheLock() {
        UUID oldCompleted = insert("COMPLETED", Duration.ofDays(9), Duration.ofDays(8));
        SimpleLock held = lockProvider.lock(new LockConfiguration(Instant.now(), EventPublicationCleanupJob.LOCK_NAME,
                Duration.ofMinutes(1), Duration.ZERO)).orElseThrow();
        try {
            assertThat(job.runLocked()).isFalse();
            assertThat(exists(oldCompleted)).isTrue();
        } finally {
            held.unlock();
        }
    }
}
