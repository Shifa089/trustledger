package com.trustledger.core.payments.application;

import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.shared.audit.AuditSnapshotSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the audit trail records about a transfer for an {@code @Audited} approve or reject (FR-A-1): status,
 * amount (decimal string, SPEC §6) and reviewer. Not the note, which is the sender's free text.
 */
@Service
class TransferAuditSnapshotSource implements AuditSnapshotSource {

    private final TransferRepository transfers;

    TransferAuditSnapshotSource(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    public String entityType() {
        return "TRANSFER";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> snapshot(UUID id) {
        return transfers.findById(id).map(TransferAuditSnapshotSource::of);
    }

    static Map<String, Object> of(Transfer transfer) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", transfer.getStatus().name());
        state.put("amount", transfer.amount().toDecimalString());
        state.put("currency", transfer.amount().currency().getCurrencyCode());
        if (transfer.getReviewedBy() != null) {
            state.put("reviewedBy", transfer.getReviewedBy().toString());
        }
        return state;
    }
}
