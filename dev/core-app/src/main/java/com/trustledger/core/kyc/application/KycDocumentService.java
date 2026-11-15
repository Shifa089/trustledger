package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.domain.DocumentFormat;
import com.trustledger.core.kyc.domain.DocumentStorage;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycDocument;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Customer document upload (FR-KYC-2). The order of the steps is the point:
 * <ol>
 *   <li>Short read-only transaction: the caller owns the case, it is SUBMITTED, and has room.</li>
 *   <li>Checks on the bytes: not empty, at most the configured size, PDF/JPEG/PNG by magic bytes.</li>
 *   <li>SHA-256, then the upload to S3 under a fresh random key, <em>outside any transaction</em>.</li>
 *   <li>Short transaction: lock the case, check again, insert the metadata row.</li>
 *   <li>If step 4 fails, delete the S3 object again (best effort), so no orphan is left behind.</li>
 * </ol>
 * Not {@code @Transactional} itself on purpose: S3 is a remote call and must not run inside a transaction.
 */
@Service
public class KycDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KycDocumentService.class);

    private final KycDocumentTransactions transactions;
    private final DocumentStorage storage;
    private final KycDocumentProperties properties;

    public KycDocumentService(KycDocumentTransactions transactions, DocumentStorage storage,
            KycDocumentProperties properties) {
        this.transactions = transactions;
        this.storage = storage;
        this.properties = properties;
    }

    public KycDocumentView upload(UUID userId, UUID caseId, DocumentType type, byte[] content) {
        Objects.requireNonNull(type, "type");
        transactions.checkUploadAllowed(userId, caseId);

        if (content == null || content.length == 0) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "The file is empty.");
        }
        if (content.length > properties.maxSize().toBytes()) {
            throw new TrustLedgerException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "A document can be at most " + properties.maxSize().toBytes() + " bytes.");
        }
        DocumentFormat format = DocumentFormat.detect(content)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
                        "Only PDF, JPEG and PNG files are accepted."));

        String sha256 = sha256Hex(content);
        // Random, meaningless key: no user data, no file name, nothing guessable.
        String key = "kyc/" + caseId + "/" + UUID.randomUUID();
        storage.put(key, content, format.contentType(), sha256);

        KycDocument document;
        try {
            document = transactions.record(userId, caseId, type, key, format, content.length, sha256);
        } catch (RuntimeException failure) {
            deleteQuietly(key);
            throw failure;
        }
        log.info("KYC document {} ({}, {}, {} bytes) added to case {}", document.getId(), type,
                format, content.length, caseId);
        return KycDocumentView.of(document);
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException cleanupFailure) {
            // The original failure matters more; an orphan object is harmless and can be swept later.
            log.warn("Could not delete orphaned KYC object {}", key, cleanupFailure);
        }
    }

    static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
