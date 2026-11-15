package com.trustledger.core.kyc.application;

import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycDocument;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * {@code trustledger.kyc.documents.*} (FR-KYC-2). Startup fails on a value the regulation or the database
 * would not accept, instead of failing on the first upload.
 *
 * @param maxSize       largest accepted file; 1 byte to 5 MB (the database check is 5 MB)
 * @param maxPerCase    uploads per case, 1-50; stops one customer from filling the bucket
 * @param requiredTypes document types a case needs before an officer may verify it; not empty
 * @param viewLinkTtl   lifetime of an officer's presigned download link; 1 second to 5 minutes
 */
@ConfigurationProperties("trustledger.kyc.documents")
public record KycDocumentProperties(DataSize maxSize, int maxPerCase, List<DocumentType> requiredTypes,
        Duration viewLinkTtl) {

    static final int MAX_PER_CASE_LIMIT = 50;
    static final Duration MAX_VIEW_LINK_TTL = Duration.ofMinutes(5);

    public KycDocumentProperties {
        if (maxSize == null || maxSize.toBytes() < 1 || maxSize.toBytes() > KycDocument.MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("trustledger.kyc.documents.max-size must be 1B-5MB, was " + maxSize);
        }
        if (maxPerCase < 1 || maxPerCase > MAX_PER_CASE_LIMIT) {
            throw new IllegalArgumentException(
                    "trustledger.kyc.documents.max-per-case must be 1-" + MAX_PER_CASE_LIMIT + ", was " + maxPerCase);
        }
        if (requiredTypes == null || requiredTypes.isEmpty()) {
            throw new IllegalArgumentException("trustledger.kyc.documents.required-types must not be empty");
        }
        if (viewLinkTtl == null || viewLinkTtl.compareTo(Duration.ofSeconds(1)) < 0
                || viewLinkTtl.compareTo(MAX_VIEW_LINK_TTL) > 0) {
            throw new IllegalArgumentException(
                    "trustledger.kyc.documents.view-link-ttl must be 1s-5m, was " + viewLinkTtl);
        }
        requiredTypes = List.copyOf(EnumSet.copyOf(requiredTypes)); // de-duplicated, in enum order
    }

    public Set<DocumentType> requiredTypeSet() {
        return EnumSet.copyOf(requiredTypes);
    }
}
