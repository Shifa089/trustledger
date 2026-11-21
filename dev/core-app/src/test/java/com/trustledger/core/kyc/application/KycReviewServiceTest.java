package com.trustledger.core.kyc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.kyc.domain.DocumentFormat;
import com.trustledger.core.kyc.domain.DocumentStorage;
import com.trustledger.core.kyc.domain.DocumentStorage.PresignedLink;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.kyc.domain.KycDocumentRepository;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.paging.PagingProperties;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.unit.DataSize;

/** Maker-checker orchestration: required documents, event only on approval, nothing changes on a refusal. */
class KycReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID OFFICER_A = UUID.randomUUID();
    private static final UUID OFFICER_B = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();

    private final KycCaseRepository cases = mock(KycCaseRepository.class);
    private final KycDocumentRepository documents = mock(KycDocumentRepository.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private KycReviewService service;
    private KycCase kycCase;

    @BeforeEach
    void setUp() {
        KycDocumentProperties properties = new KycDocumentProperties(DataSize.ofMegabytes(5), 10,
                List.of(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF), Duration.ofMinutes(5));
        service = new KycReviewService(cases, documents, storage, properties, events, Clock.fixed(NOW, ZoneOffset.UTC),
                new PagingProperties(20, 100));
        kycCase = KycCase.submitFull(CUSTOMER, "12 Test Street, Bengaluru", NOW);
        when(cases.lockById(CASE_ID)).thenReturn(Optional.of(kycCase));
        when(cases.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void verifyNeedsEveryRequiredDocumentType() {
        withDocuments(DocumentType.PAN_CARD, DocumentType.PHOTO);

        assertThatThrownBy(() -> service.verify(CASE_ID, OFFICER_A))
                .isInstanceOfSatisfying(TrustLedgerException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_DOCUMENTS_MISSING);
                    assertThat(e.detail()).contains("ADDRESS_PROOF").doesNotContain("PAN_CARD");
                });
        assertThat(kycCase.getStatus()).isEqualTo(KycStatus.SUBMITTED);
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void verifyWithTheRequiredDocumentsRecordsTheMakerAndPublishesNothing() {
        withDocuments(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF);

        KycReviewCaseView view = service.verify(CASE_ID, OFFICER_A);

        assertThat(view.status()).isEqualTo(KycStatus.VERIFIED);
        assertThat(view.verifiedBy()).isEqualTo(OFFICER_A);
        assertThat(view.verifiedAt()).isEqualTo(NOW);
        assertThat(view.documents()).hasSize(2);
        verifyNoInteractions(events);
    }

    @Test
    void approveByASecondOfficerPublishesFullUpgrade() {
        withDocuments(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF);
        service.verify(CASE_ID, OFFICER_A);

        KycReviewCaseView view = service.approve(CASE_ID, OFFICER_B);

        assertThat(view.status()).isEqualTo(KycStatus.APPROVED);
        assertThat(view.approvedBy()).isEqualTo(OFFICER_B);
        verify(events).publishEvent(new KycLevelUpgraded(CUSTOMER, KycLevel.FULL));
    }

    @Test
    void sameOfficerApprovingIsMakerCheckerViolationAndNothingChanges() {
        withDocuments(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF);
        service.verify(CASE_ID, OFFICER_A);

        assertThatThrownBy(() -> service.approve(CASE_ID, OFFICER_A))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MAKER_CHECKER_VIOLATION));
        assertThat(kycCase.getStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(kycCase.getApprovedBy()).isNull();
        verifyNoInteractions(events);
    }

    @Test
    void approveBeforeVerifyIsAnInvalidTransition() {
        assertThatThrownBy(() -> service.approve(CASE_ID, OFFICER_B))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verifyNoInteractions(events);
    }

    @Test
    void rejectStoresTheReasonAndPublishesNothing() {
        KycReviewCaseView view = service.reject(CASE_ID, OFFICER_A, "  Address proof unreadable  ");

        assertThat(view.status()).isEqualTo(KycStatus.REJECTED);
        assertThat(view.rejectionReason()).isEqualTo("Address proof unreadable");
        assertThat(view.rejectedBy()).isEqualTo(OFFICER_A);
        verifyNoInteractions(events);
    }

    @Test
    void unknownCaseIsNotFound() {
        assertThatThrownBy(() -> service.verify(UUID.randomUUID(), OFFICER_A))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void documentLinkIsSignedForTheConfiguredTtl() {
        UUID docId = UUID.randomUUID();
        KycDocument doc = KycDocument.record(CASE_ID, DocumentType.PAN_CARD, "kyc/c/d", DocumentFormat.PNG, 10,
                "a".repeat(64), CUSTOMER, NOW);
        when(documents.findByIdAndCaseId(docId, CASE_ID)).thenReturn(Optional.of(doc));
        URI url = URI.create("http://s3.test/kyc/c/d?X-Amz-Expires=300");
        when(storage.presignGet("kyc/c/d", "image/png", Duration.ofMinutes(5)))
                .thenReturn(new PresignedLink(url, NOW.plusSeconds(300)));

        DocumentLinkView link = service.documentLink(CASE_ID, docId, OFFICER_A);

        assertThat(link.url()).isEqualTo(url);
        assertThat(link.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(link.toString()).doesNotContain("s3.test");
    }

    @Test
    void documentOfAnotherCaseIsNotFoundAndNothingIsSigned() {
        assertThatThrownBy(() -> service.documentLink(CASE_ID, UUID.randomUUID(), OFFICER_A))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(storage);
    }

    @Test
    void queueRejectsAPageSizeAboveTheCap() {
        assertThatThrownBy(() -> service.queue(KycStatus.SUBMITTED, 0, 101))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void reviewViewToStringMasksTheAddress() {
        KycReviewCaseView view = KycReviewCaseView.of(kycCase, List.of());
        assertThat(view.toString()).doesNotContain("Test Street");
    }

    private void withDocuments(DocumentType... types) {
        List<KycDocument> docs = java.util.Arrays.stream(types)
                .map(t -> KycDocument.record(CASE_ID, t, "kyc/" + t, DocumentFormat.PDF, 10, "a".repeat(64), CUSTOMER,
                        NOW))
                .toList();
        when(documents.findByCaseIdOrderByCreatedAtAsc(any())).thenReturn(docs);
    }
}
