package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.kyc.domain.KycDocumentRepository;
import com.trustledger.core.shared.audit.AuditSnapshotSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the audit trail records about KYC cases and documents for an {@code @Audited} officer action (FR-A-1): the
 * level, status and who decided. Never personal data (name, PAN, date of birth, address), the rejection reason
 * (free text) or a document's S3 key.
 */
final class KycAuditSnapshots {

    static final String KYC_CASE = "KYC_CASE";
    static final String KYC_DOCUMENT = "KYC_DOCUMENT";

    private KycAuditSnapshots() {
    }

    static Map<String, Object> of(KycCase kycCase) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("level", kycCase.getLevel().name());
        state.put("status", kycCase.getStatus().name());
        putId(state, "verifiedBy", kycCase.getVerifiedBy());
        putId(state, "approvedBy", kycCase.getApprovedBy());
        putId(state, "rejectedBy", kycCase.getRejectedBy());
        return state;
    }

    static Map<String, Object> of(KycDocument document) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("caseId", document.getCaseId().toString());
        state.put("documentType", document.getType().name());
        return state;
    }

    private static void putId(Map<String, Object> state, String key, UUID id) {
        if (id != null) {
            state.put(key, id.toString());
        }
    }

    @Service
    static class CaseSource implements AuditSnapshotSource {

        private final KycCaseRepository cases;

        CaseSource(KycCaseRepository cases) {
            this.cases = cases;
        }

        @Override
        public String entityType() {
            return KYC_CASE;
        }

        @Override
        @Transactional(readOnly = true)
        public Optional<Map<String, Object>> snapshot(UUID id) {
            return cases.findById(id).map(KycAuditSnapshots::of);
        }
    }

    @Service
    static class DocumentSource implements AuditSnapshotSource {

        private final KycDocumentRepository documents;

        DocumentSource(KycDocumentRepository documents) {
            this.documents = documents;
        }

        @Override
        public String entityType() {
            return KYC_DOCUMENT;
        }

        @Override
        @Transactional(readOnly = true)
        public Optional<Map<String, Object>> snapshot(UUID id) {
            return documents.findById(id).map(KycAuditSnapshots::of);
        }
    }
}
