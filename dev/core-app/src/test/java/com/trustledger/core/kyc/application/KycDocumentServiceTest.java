package com.trustledger.core.kyc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.kyc.domain.DocumentFormat;
import com.trustledger.core.kyc.domain.DocumentStorage;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.unit.DataSize;

/** The order of the upload steps, and the S3 clean-up when the database step fails. */
class KycDocumentServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final byte[] PDF = "%PDF-1.7\nfake test document".getBytes(StandardCharsets.US_ASCII);
    /** printf '%PDF-1.7\nfake test document' | sha256sum */
    private static final String PDF_SHA = KycDocumentService.sha256Hex(PDF);

    private final KycDocumentTransactions transactions = mock(KycDocumentTransactions.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private KycDocumentService service;

    @BeforeEach
    void setUp() {
        KycDocumentProperties properties = new KycDocumentProperties(DataSize.ofBytes(64), 10,
                List.of(DocumentType.PAN_CARD), Duration.ofMinutes(5));
        service = new KycDocumentService(transactions, storage, properties);
        when(transactions.record(any(), any(), any(), anyString(), any(), anyLong(), anyString()))
                .thenAnswer(call -> KycDocument.record(call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(0),
                        Instant.parse("2026-11-15T10:00:00Z")));
    }

    @Test
    void checksThenUploadsThenRecords() {
        KycDocumentView view = service.upload(USER, CASE, DocumentType.PAN_CARD, PDF);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(transactions, storage);
        order.verify(transactions).checkUploadAllowed(USER, CASE);
        order.verify(storage).put(key.capture(), eq(PDF), eq("application/pdf"), eq(PDF_SHA));
        order.verify(transactions).record(USER, CASE, DocumentType.PAN_CARD, key.getValue(), DocumentFormat.PDF,
                PDF.length, PDF_SHA);

        assertThat(key.getValue()).matches("kyc/" + CASE + "/[0-9a-f-]{36}");
        assertThat(view.sha256()).isEqualTo(PDF_SHA).hasSize(64);
        assertThat(view.contentType()).isEqualTo("application/pdf");
        assertThat(view.sizeBytes()).isEqualTo(PDF.length);
    }

    @Test
    void shaIsTheStandardSha256() {
        assertThat(KycDocumentService.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void notOwnedCaseStopsBeforeTheFileIsEvenLookedAt() {
        doThrow(new TrustLedgerException(ErrorCode.NOT_FOUND, "x")).when(transactions).checkUploadAllowed(USER, CASE);

        assertError(() -> service.upload(USER, CASE, DocumentType.PAN_CARD, PDF), ErrorCode.NOT_FOUND);
        verifyNoInteractions(storage);
    }

    @Test
    void emptyFileIsValidationFailedAndNothingIsStored() {
        assertError(() -> service.upload(USER, CASE, DocumentType.PAN_CARD, new byte[0]), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(storage);
    }

    @Test
    void oneByteOverTheLimitIsTooLargeExactlyTheLimitIsFine() {
        byte[] atLimit = Arrays.copyOf(PDF, 64);
        byte[] overLimit = Arrays.copyOf(PDF, 65);

        service.upload(USER, CASE, DocumentType.PAN_CARD, atLimit);
        assertError(() -> service.upload(USER, CASE, DocumentType.PAN_CARD, overLimit), ErrorCode.DOCUMENT_TOO_LARGE);
        verify(storage).put(anyString(), eq(atLimit), anyString(), anyString());
        verify(storage, never()).put(anyString(), eq(overLimit), anyString(), anyString());
    }

    @Test
    void wrongMagicBytesAreUnsupportedAndNothingIsStored() {
        byte[] exe = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03};

        assertError(() -> service.upload(USER, CASE, DocumentType.PAN_CARD, exe), ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
        verifyNoInteractions(storage);
    }

    @Test
    void whenTheDatabaseStepFailsTheS3ObjectIsDeletedAgain() {
        doThrow(new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION, "verified meanwhile"))
                .when(transactions).record(any(), any(), any(), anyString(), any(), anyLong(), anyString());

        assertError(() -> service.upload(USER, CASE, DocumentType.PAN_CARD, PDF), ErrorCode.INVALID_STATE_TRANSITION);

        ArgumentCaptor<String> put = ArgumentCaptor.forClass(String.class);
        verify(storage).put(put.capture(), any(), anyString(), anyString());
        verify(storage).delete(put.getValue());
    }

    @Test
    void aFailingCleanupDoesNotHideTheOriginalError() {
        doThrow(new DataIntegrityViolationException("ck_kyc_documents_size"))
                .when(transactions).record(any(), any(), any(), anyString(), any(), anyLong(), anyString());
        doThrow(new TrustLedgerException(ErrorCode.STORAGE_UNAVAILABLE, "down")).when(storage).delete(anyString());

        assertThatThrownBy(() -> service.upload(USER, CASE, DocumentType.PAN_CARD, PDF))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void s3FailureIsReportedAndNothingIsRecorded() {
        doThrow(new TrustLedgerException(ErrorCode.STORAGE_UNAVAILABLE, "down"))
                .when(storage).put(anyString(), any(), anyString(), anyString());

        assertError(() -> service.upload(USER, CASE, DocumentType.PAN_CARD, PDF), ErrorCode.STORAGE_UNAVAILABLE);
        verify(transactions, never()).record(any(), any(), any(), anyString(), any(), anyLong(), anyString());
    }

    private static void assertError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
