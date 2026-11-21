package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.kyc.domain.DocumentStorage;
import com.trustledger.core.kyc.domain.DocumentStorage.PresignedLink;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.kyc.domain.KycDocumentRepository;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.paging.PagingProperties;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compliance review of full KYC (FR-KYC-3, FR-KYC-4): the queue, the maker step (verify), the checker step
 * (approve, by a different officer) and rejection with a reason. The state rules live in {@link KycCase};
 * this service adds what needs other data: the required documents and the event.
 * <p>
 * Decisions lock the case row ({@code FOR UPDATE}): two officers acting at once are serialised, and the
 * second sees the first one's result (for example 409 when the case is already approved).
 */
@Service
public class KycReviewService {

    private static final Logger log = LoggerFactory.getLogger(KycReviewService.class);

    private final KycCaseRepository cases;
    private final KycDocumentRepository documents;
    private final DocumentStorage storage;
    private final KycDocumentProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PagingProperties paging;

    public KycReviewService(KycCaseRepository cases, KycDocumentRepository documents, DocumentStorage storage,
            KycDocumentProperties properties, ApplicationEventPublisher events, Clock clock,
            PagingProperties paging) {
        this.cases = cases;
        this.documents = documents;
        this.storage = storage;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.paging = paging;
    }

    /** FULL cases in one status, oldest first (first in, first reviewed). A missing size uses the default. */
    @Transactional(readOnly = true)
    public KycQueuePage queue(KycStatus status, int page, Integer requestedSize) {
        int size = paging.checkedSize(page, requestedSize);
        Page<KycCase> result = cases.findByLevelAndStatus(KycLevel.FULL, status,
                PageRequest.of(page, size, Sort.by("createdAt").ascending().and(Sort.by("id"))));
        List<UUID> ids = result.getContent().stream().map(KycCase::getId).toList();
        Map<UUID, List<KycDocument>> byCase = ids.isEmpty() ? Map.of()
                : documents.findByCaseIdIn(ids).stream().collect(Collectors.groupingBy(KycDocument::getCaseId));
        List<KycQueueItem> items = result.getContent().stream()
                .map(c -> KycQueueItem.of(c, byCase.getOrDefault(c.getId(), List.of())))
                .toList();
        return new KycQueuePage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public KycReviewCaseView detail(UUID caseId) {
        KycCase kycCase = cases.findById(caseId).orElseThrow(KycReviewService::notFound);
        return view(kycCase);
    }

    /** Maker step: SUBMITTED -> VERIFIED, only with every required document type uploaded. */
    @Transactional
    public KycReviewCaseView verify(UUID caseId, UUID officerId) {
        KycCase kycCase = lock(caseId);
        if (kycCase.getLevel() == KycLevel.FULL && kycCase.getStatus() == KycStatus.SUBMITTED) {
            requireDocuments(kycCase);
        }
        kycCase.verify(officerId, clock.instant());
        cases.saveAndFlush(kycCase);
        log.info("KYC case {} verified by officer {}", caseId, officerId);
        return view(kycCase);
    }

    /** Checker step: VERIFIED -> APPROVED by a different officer; publishes {@link KycLevelUpgraded}(FULL). */
    @Transactional
    public KycReviewCaseView approve(UUID caseId, UUID officerId) {
        KycCase kycCase = lock(caseId);
        kycCase.approve(officerId, clock.instant());
        // Flush before publishing: if the database refuses (maker-checker check), no event goes out.
        cases.saveAndFlush(kycCase);
        events.publishEvent(new KycLevelUpgraded(kycCase.getUserId(), KycLevel.FULL));
        log.info("KYC case {} approved by officer {}", caseId, officerId);
        return view(kycCase);
    }

    @Transactional
    public KycReviewCaseView reject(UUID caseId, UUID officerId, String reason) {
        KycCase kycCase = lock(caseId);
        kycCase.reject(officerId, reason, clock.instant());
        cases.saveAndFlush(kycCase);
        log.info("KYC case {} rejected by officer {}", caseId, officerId); // the reason may quote personal data
        return view(kycCase);
    }

    /**
     * A presigned download link, valid for {@code view-link-ttl} (5 minutes). Signing is a local computation,
     * no call to S3. Every access is logged with the officer; it becomes an audit event in M10.
     */
    @Transactional(readOnly = true)
    public DocumentLinkView documentLink(UUID caseId, UUID documentId, UUID officerId) {
        KycDocument document = documents.findByIdAndCaseId(documentId, caseId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "KYC document not found."));
        PresignedLink link = storage.presignGet(document.getS3Key(), document.getContentType(),
                properties.viewLinkTtl());
        log.info("KYC document {} of case {} opened by officer {}", documentId, caseId, officerId);
        return new DocumentLinkView(link.url(), link.expiresAt());
    }

    private void requireDocuments(KycCase kycCase) {
        Set<DocumentType> missing = properties.requiredTypeSet();
        documents.findByCaseIdOrderByCreatedAtAsc(kycCase.getId()).forEach(d -> missing.remove(d.getType()));
        if (!missing.isEmpty()) {
            throw new TrustLedgerException(ErrorCode.KYC_DOCUMENTS_MISSING,
                    "Required documents missing: " + EnumSet.copyOf(missing) + ".");
        }
    }

    private KycCase lock(UUID caseId) {
        return cases.lockById(caseId).orElseThrow(KycReviewService::notFound);
    }

    private KycReviewCaseView view(KycCase kycCase) {
        return KycReviewCaseView.of(kycCase, documents.findByCaseIdOrderByCreatedAtAsc(kycCase.getId()));
    }

    private static TrustLedgerException notFound() {
        return new TrustLedgerException(ErrorCode.NOT_FOUND, "KYC case not found.");
    }
}
