package com.trustledger.core.kyc.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for KYC document metadata; implemented by Spring Data in {@code kyc.infrastructure}. */
public interface KycDocumentRepository {

    /** Flushes at once, so a constraint violation surfaces in the caller (which then cleans up S3). */
    KycDocument saveAndFlush(KycDocument document);

    List<KycDocument> findByCaseIdOrderByCreatedAtAsc(UUID caseId);

    List<KycDocument> findByCaseIdIn(Collection<UUID> caseIds);

    /** The document only if it belongs to that case, so a case ID cannot be paired with any document ID. */
    Optional<KycDocument> findByIdAndCaseId(UUID id, UUID caseId);

    long countByCaseId(UUID caseId);
}
