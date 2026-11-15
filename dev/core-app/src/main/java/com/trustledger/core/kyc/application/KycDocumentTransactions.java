package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.domain.DocumentFormat;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.kyc.domain.KycDocumentRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short database transactions around a document upload. They live in their own bean so that
 * {@link KycDocumentService} can call S3 <em>between</em> them, outside any transaction and without holding
 * a row lock (CLAUDE.md: no remote calls while holding locks).
 */
@Service
class KycDocumentTransactions {

    private final KycCaseRepository cases;
    private final KycDocumentRepository documents;
    private final KycDocumentProperties properties;
    private final Clock clock;

    KycDocumentTransactions(KycCaseRepository cases, KycDocumentRepository documents,
            KycDocumentProperties properties, Clock clock) {
        this.cases = cases;
        this.documents = documents;
        this.properties = properties;
        this.clock = clock;
    }

    /** Fail fast, before the upload: the caller owns the case, it still takes documents, and has room. */
    @Transactional(readOnly = true)
    public void checkUploadAllowed(UUID userId, UUID caseId) {
        requireAccepts(owned(cases.findById(caseId).orElse(null), userId));
    }

    /**
     * Records the uploaded object. The case is locked ({@code FOR UPDATE}) and re-checked: an officer may have
     * verified it, or a parallel upload may have taken the last slot, while the file was on its way to S3.
     */
    @Transactional
    public KycDocument record(UUID userId, UUID caseId, DocumentType type, String s3Key, DocumentFormat format,
            long sizeBytes, String sha256) {
        KycCase kycCase = owned(cases.lockById(caseId).orElse(null), userId);
        requireAccepts(kycCase);
        return documents.saveAndFlush(
                KycDocument.record(caseId, type, s3Key, format, sizeBytes, sha256, userId, clock.instant()));
    }

    private void requireAccepts(KycCase kycCase) {
        kycCase.requireAcceptsDocuments();
        if (documents.countByCaseId(kycCase.getId()) >= properties.maxPerCase()) {
            throw new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED,
                    "A KYC case can have at most " + properties.maxPerCase() + " documents.");
        }
    }

    /** Someone else's case is reported exactly like a missing one, so case IDs cannot be probed (BOLA). */
    private static KycCase owned(KycCase kycCase, UUID userId) {
        if (kycCase == null || !kycCase.isOwnedBy(userId)) {
            throw new TrustLedgerException(ErrorCode.NOT_FOUND, "KYC case not found.");
        }
        return kycCase;
    }
}
