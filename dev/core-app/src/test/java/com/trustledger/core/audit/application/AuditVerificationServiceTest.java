package com.trustledger.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.audit.domain.AuditEventStore;
import com.trustledger.core.audit.domain.AuditHashing;
import com.trustledger.core.audit.domain.ChainVerification;
import com.trustledger.core.audit.domain.StoredAuditEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The walk reads the chain in keyset batches and stops at the first break. */
class AuditVerificationServiceTest {

    private final AuditEventStore store = mock(AuditEventStore.class);
    private final AuditVerificationService service = new AuditVerificationService(store, new AuditProperties(2));

    private static List<StoredAuditEvent> chain(int n) {
        List<StoredAuditEvent> rows = new ArrayList<>();
        String prev = AuditHashing.GENESIS_HASH;
        for (int i = 1; i <= n; i++) {
            AuditEvent e = new AuditEvent(Instant.EPOCH.plusSeconds(i), null, "SYSTEM", "WALLET_CREATED", "WALLET",
                    UUID.randomUUID(), null, null, null, null);
            String hash = AuditHashing.hash(prev, e);
            rows.add(new StoredAuditEvent(i, e.occurredAt(), null, "SYSTEM", e.action(), e.entityType(), e.entityId(),
                    null, null, null, null, null, prev, hash));
            prev = hash;
        }
        return rows;
    }

    private void serve(List<StoredAuditEvent> rows) {
        when(store.chainAfter(anyLong(), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(0);
            int limit = call.getArgument(1);
            return rows.stream().filter(r -> r.seq() > after).limit(limit).toList();
        });
    }

    @Test
    void walksEveryBatchOfAnIntactChain() {
        serve(chain(5));

        ChainVerification result = service.verify();

        assertThat(result.status()).isEqualTo(ChainVerification.Status.OK);
        assertThat(result.checkedEvents()).isEqualTo(5);
        verify(store).chainAfter(0, 2);
        verify(store).chainAfter(2, 2);
        verify(store).chainAfter(4, 2);
    }

    @Test
    void aFullLastBatchCostsOneEmptyRead() {
        serve(chain(4));

        assertThat(service.verify().checkedEvents()).isEqualTo(4);
        verify(store).chainAfter(4, 2);
    }

    @Test
    void stopsReadingAtTheFirstBreak() {
        List<StoredAuditEvent> rows = chain(6);
        rows.remove(1);
        serve(rows);

        ChainVerification result = service.verify();

        assertThat(result.brokenSeq()).isEqualTo(3L);
        verify(store, never()).chainAfter(3, 2);
    }

    @Test
    void anEmptyTableIsOk() {
        serve(List.of());

        assertThat(service.verify().status()).isEqualTo(ChainVerification.Status.OK);
    }
}
