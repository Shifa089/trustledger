package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.payments.domain.IdempotencyKeyRecord;
import com.trustledger.core.payments.domain.IdempotencyKeyRepository;
import com.trustledger.core.payments.domain.IdempotencyStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    static PaymentsProperties properties() {
        return new PaymentsProperties(ZoneId.of("Asia/Kolkata"), new PaymentsProperties.Idempotency(
                Duration.ofHours(24), DataSize.ofKilobytes(16), "0 15 * * * *", 1000, Duration.ofMinutes(10)),
                new PaymentsProperties.Topups(Money.of(100, Money.INR), false));
    }

    private final IdempotencyKeyRepository keys = mock(IdempotencyKeyRepository.class);
    private final IdempotencyService service =
            new IdempotencyService(keys, properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static IdempotencyKeyRecord row(String hash, IdempotencyStatus status, Integer code, String body) {
        return new IdempotencyKeyRecord(USER, KEY, hash, status, code, body, NOW.plusSeconds(3600));
    }

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
    }

    @Test
    void newKeyIsClaimedFor24Hours() {
        when(keys.claim(USER, KEY, HASH, NOW, NOW.plus(Duration.ofHours(24)))).thenReturn(true);

        assertThat(service.claim(USER, KEY, HASH)).isInstanceOf(IdempotencyClaim.Claimed.class);
    }

    @Test
    void completedKeyWithSameBodyReplays() {
        when(keys.claim(any(), any(), anyString(), any(), any())).thenReturn(false);
        when(keys.find(USER, KEY)).thenReturn(Optional.of(row(HASH, IdempotencyStatus.COMPLETED, 201, "{\"id\":1}")));

        assertThat(service.claim(USER, KEY, HASH)).isEqualTo(new IdempotencyClaim.Replay(201, "{\"id\":1}"));
    }

    @Test
    void inProgressKeyIs409() {
        when(keys.claim(any(), any(), anyString(), any(), any())).thenReturn(false);
        when(keys.find(USER, KEY)).thenReturn(Optional.of(row(HASH, IdempotencyStatus.IN_PROGRESS, null, null)));

        refused(() -> service.claim(USER, KEY, HASH), ErrorCode.REQUEST_IN_PROGRESS);
    }

    @Test
    void differentBodyIs422WhateverTheState() {
        when(keys.claim(any(), any(), anyString(), any(), any())).thenReturn(false);
        when(keys.find(USER, KEY)).thenReturn(Optional.of(row(HASH, IdempotencyStatus.COMPLETED, 201, "{}")));
        refused(() -> service.claim(USER, KEY, OTHER_HASH), ErrorCode.IDEMPOTENCY_KEY_REUSED);

        when(keys.find(USER, KEY)).thenReturn(Optional.of(row(HASH, IdempotencyStatus.IN_PROGRESS, null, null)));
        refused(() -> service.claim(USER, KEY, OTHER_HASH), ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void keyReleasedInBetweenIsClaimedOnRetry() {
        when(keys.claim(any(), any(), anyString(), any(), any())).thenReturn(false, true);
        when(keys.find(USER, KEY)).thenReturn(Optional.empty());

        assertThat(service.claim(USER, KEY, HASH)).isInstanceOf(IdempotencyClaim.Claimed.class);
        verify(keys, times(2)).claim(eq(USER), eq(KEY), eq(HASH), any(), any());
    }

    @Test
    void keyThatKeepsVanishingGivesUpWith409() {
        when(keys.claim(any(), any(), anyString(), any(), any())).thenReturn(false);
        when(keys.find(USER, KEY)).thenReturn(Optional.empty());

        refused(() -> service.claim(USER, KEY, HASH), ErrorCode.REQUEST_IN_PROGRESS);
        verify(keys, times(3)).claim(any(), any(), anyString(), any(), any());
    }

    @Test
    void onlySuccessfulResponsesAreStored() {
        when(keys.complete(USER, KEY, 201, "{}")).thenReturn(true);

        assertThat(service.complete(USER, KEY, 201, "{}")).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> service.complete(USER, KEY, 422, "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.complete(USER, KEY, 199, "{}"));
    }

    @Test
    void releaseAndCleanupDelegate() {
        when(keys.release(USER, KEY)).thenReturn(true);
        when(keys.deleteExpired(NOW, 500)).thenReturn(7);

        assertThat(service.release(USER, KEY)).isTrue();
        assertThat(service.deleteExpiredBatch(500)).isEqualTo(7);
    }
}
