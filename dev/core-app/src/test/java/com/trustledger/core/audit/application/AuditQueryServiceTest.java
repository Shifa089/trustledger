package com.trustledger.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.audit.domain.AuditEventFilter;
import com.trustledger.core.audit.domain.AuditEventStore;
import com.trustledger.core.audit.domain.StoredAuditEvent;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.paging.PagingProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditQueryServiceTest {

    private final AuditEventStore store = mock(AuditEventStore.class);
    private final AuditQueryService service = new AuditQueryService(store, new PagingProperties(20, 100));

    @Test
    void pagesAreReadWithOffsetAndCounted() {
        AuditEventFilter filter = new AuditEventFilter("WALLET", null, null, null, null, null);
        StoredAuditEvent row = new StoredAuditEvent(7, Instant.EPOCH, null, "SYSTEM", "WALLET_CREATED", "WALLET",
                UUID.randomUUID(), null, Map.of("status", "ACTIVE"), null, null, "c", "0".repeat(64), "a".repeat(64));
        when(store.count(filter)).thenReturn(41L);
        when(store.search(filter, 40, 20)).thenReturn(List.of(row));

        AuditEventPage page = service.search(filter, 2, null);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.seq()).isEqualTo(7);
            assertThat(item.after()).isEqualTo(Map.of("status", "ACTIVE"));
            assertThat(item.hash()).isEqualTo("a".repeat(64));
        });
        verify(store).search(filter, 40, 20);
    }

    @Test
    void anOversizedPageIsRefused() {
        assertThatThrownBy(() -> service.search(AuditEventFilter.NONE, 0, 101))
                .isInstanceOf(TrustLedgerException.class);
    }

    @Test
    void noRowsIsZeroPages() {
        when(store.count(AuditEventFilter.NONE)).thenReturn(0L);

        assertThat(service.search(AuditEventFilter.NONE, 0, 10).totalPages()).isZero();
    }
}
