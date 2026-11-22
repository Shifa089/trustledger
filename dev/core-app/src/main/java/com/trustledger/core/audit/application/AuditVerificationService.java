package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.AuditEventStore;
import com.trustledger.core.audit.domain.ChainVerification;
import com.trustledger.core.audit.domain.ChainVerifier;
import com.trustledger.core.audit.domain.StoredAuditEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /audit/verify} (FR-A-2): walks the whole chain in {@code seq} order, in batches, and reports the first
 * broken row.
 * <p>
 * REPEATABLE READ: every batch reads the same snapshot, so rows committed during the walk are simply not part of
 * it. It takes no lock, so writers are never held up by an auditor.
 */
@Service
public class AuditVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AuditVerificationService.class);

    private final AuditEventStore store;
    private final AuditProperties properties;

    public AuditVerificationService(AuditEventStore store, AuditProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChainVerification verify() {
        ChainVerifier verifier = new ChainVerifier();
        long afterSeq = 0;
        List<StoredAuditEvent> batch;
        do {
            batch = store.chainAfter(afterSeq, properties.verifyBatchSize());
            for (StoredAuditEvent row : batch) {
                if (!verifier.accept(row)) {
                    ChainVerification result = verifier.result();
                    log.warn("Audit chain broken at seq {}: {}", result.brokenSeq(), result.reason());
                    return result;
                }
                afterSeq = row.seq();
            }
        } while (batch.size() == properties.verifyBatchSize());
        return verifier.result();
    }
}
