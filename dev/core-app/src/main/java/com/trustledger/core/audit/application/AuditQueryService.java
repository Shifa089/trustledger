package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.AuditEventFilter;
import com.trustledger.core.audit.domain.AuditEventStore;
import com.trustledger.core.shared.paging.PagingProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code GET /audit/events} (SPEC §5, AUDITOR): the trail, newest first, filtered and paginated. */
@Service
public class AuditQueryService {

    private final AuditEventStore store;
    private final PagingProperties paging;

    public AuditQueryService(AuditEventStore store, PagingProperties paging) {
        this.store = store;
        this.paging = paging;
    }

    /** @throws com.trustledger.core.shared.TrustLedgerException {@code VALIDATION_FAILED} for a bad page or size */
    @Transactional(readOnly = true)
    public AuditEventPage search(AuditEventFilter filter, int page, Integer requestedSize) {
        int size = paging.checkedSize(page, requestedSize);
        long total = store.count(filter);
        List<AuditEventView> items = store.search(filter, (long) page * size, size).stream()
                .map(AuditEventView::of)
                .toList();
        return new AuditEventPage(items, page, size, total, (int) ((total + size - 1) / size));
    }
}
