package com.trustledger.core.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-11-16T20:30:00Z");
    private static final ReconciliationReport REPORT = new ReconciliationReport(3, 0, 0, List.of(), false, NOW, NOW);

    private final ReconciliationService service = mock(ReconciliationService.class);
    private final LockProvider lockProvider = mock(LockProvider.class);
    private final ReconciliationProperties properties = new ReconciliationProperties("0 0 2 * * *",
            ZoneId.of("Asia/Kolkata"), 500, 100, Duration.ofMinutes(30), Duration.ofMinutes(1));
    private final ReconciliationJob job = new ReconciliationJob(service, new DefaultLockingTaskExecutor(lockProvider),
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

    private ArgumentCaptor<LockConfiguration> lockFree() {
        ArgumentCaptor<LockConfiguration> lock = ArgumentCaptor.forClass(LockConfiguration.class);
        when(lockProvider.lock(lock.capture())).thenReturn(Optional.of(mock(SimpleLock.class)));
        when(service.reconcile()).thenReturn(REPORT);
        return lock;
    }

    @Test
    void manualRunReturnsTheReportAndKeepsTheLockOnlyWhileRunning() {
        ArgumentCaptor<LockConfiguration> lock = lockFree();

        assertThat(job.runNow()).isEqualTo(REPORT);

        assertThat(lock.getValue().getName()).isEqualTo(ReconciliationJob.LOCK_NAME);
        assertThat(lock.getValue().getLockAtMostFor()).isEqualTo(Duration.ofMinutes(30));
        assertThat(lock.getValue().getLockAtLeastFor()).isEqualTo(Duration.ZERO);
    }

    @Test
    void manualRunWhileLockedIsAConflict() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        assertThatThrownBy(job::runNow).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS);
        verify(service, never()).reconcile();
    }

    @Test
    void nightlyRunKeepsTheLockForTheConfiguredMinimum() {
        ArgumentCaptor<LockConfiguration> lock = lockFree();

        job.nightly();

        verify(service).reconcile();
        assertThat(lock.getValue().getLockAtLeastFor()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void nightlyRunSkipsWhenAnotherInstanceHoldsTheLock() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        job.nightly();

        verify(service, never()).reconcile();
    }

    @Test
    void failureInTheRunPropagates() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        when(service.reconcile()).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(job::runNow).isInstanceOf(IllegalStateException.class).hasMessage("db down");
    }
}
