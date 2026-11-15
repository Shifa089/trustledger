package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycDocument;
import java.time.Instant;
import java.util.UUID;

/** Document metadata. Never the S3 key (internal) and never the file name (may hold personal data). */
public record KycDocumentView(UUID id, DocumentType type, String contentType, long sizeBytes, String sha256,
        Instant uploadedAt) {

    static KycDocumentView of(KycDocument document) {
        return new KycDocumentView(document.getId(), document.getType(), document.getContentType(),
                document.getSizeBytes(), document.getSha256(), document.getCreatedAt());
    }
}
