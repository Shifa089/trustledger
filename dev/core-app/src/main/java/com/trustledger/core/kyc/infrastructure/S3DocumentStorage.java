package com.trustledger.core.kyc.infrastructure;

import com.trustledger.core.kyc.domain.DocumentStorage;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/** {@link DocumentStorage} on S3 (AWS SDK v2). Vendor exceptions never leave this class. */
@Component
class S3DocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3DocumentStorage(S3Client s3, S3Presigner presigner, KycStorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = properties.bucket();
    }

    /**
     * Server-side encryption (SSE-S3, AES-256) on every object. The SHA-256 goes along as
     * {@code x-amz-checksum-sha256}: S3 recomputes it and rejects the upload if a single byte differs.
     */
    @Override
    public void put(String key, byte[] content, String contentType, String sha256Hex) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength((long) content.length)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex)))
                .build();
        try {
            s3.putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw unavailable("store", key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw unavailable("delete", key, e);
        }
    }

    /** {@code Content-Disposition: attachment}: the browser downloads the file instead of rendering it inline. */
    @Override
    public PresignedLink presignGet(String key, String contentType, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentType(contentType)
                .responseContentDisposition("attachment")
                .build();
        try {
            PresignedGetObjectRequest presigned = presigner.presignGetObject(
                    GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build());
            return new PresignedLink(presigned.url().toURI(), presigned.expiration());
        } catch (SdkException | URISyntaxException e) {
            throw unavailable("sign a link for", key, e);
        }
    }

    private static TrustLedgerException unavailable(String action, String key, Exception cause) {
        log.warn("Could not {} KYC object {}", action, key, cause);
        return new TrustLedgerException(ErrorCode.STORAGE_UNAVAILABLE,
                "Document storage is unavailable. Please try again later.");
    }
}
