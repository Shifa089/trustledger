package com.trustledger.core.privacy.infrastructure;

import com.trustledger.core.privacy.domain.ExportStorage;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.net.URISyntaxException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * {@link ExportStorage} on S3 (AWS SDK v2, ADR 0011). Vendor exceptions never leave this class.
 * <p>
 * It owns its S3 client and presigner instead of publishing them as beans: the KYC module already has an
 * {@code S3Client} bean injected by type, and a second one would make that injection ambiguous. Nothing connects
 * at startup.
 */
class S3ExportStorage implements ExportStorage, DisposableBean {

    static final String JSON = "application/json";

    private static final Logger log = LoggerFactory.getLogger(S3ExportStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3ExportStorage(PrivacyStorageProperties properties) {
        this.bucket = properties.bucket();
        S3Configuration configuration = S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle())
                .build();
        AwsCredentialsProvider credentials = credentials(properties);
        S3ClientBuilder client = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(configuration);
        S3Presigner.Builder signer = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(configuration);
        if (properties.endpoint() != null) {
            client.endpointOverride(properties.endpoint());
            signer.endpointOverride(properties.endpoint());
        }
        this.s3 = client.build();
        this.presigner = signer.build();
    }

    /** Server-side encryption (SSE-S3, AES-256) on every object: the file holds personal data in clear. */
    @Override
    public void putJson(String key, byte[] json) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(JSON)
                .contentLength((long) json.length)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build();
        try {
            s3.putObject(request, RequestBody.fromBytes(json));
        } catch (SdkException e) {
            throw unavailable("store", key, e);
        }
    }

    /** {@code Content-Disposition: attachment}: the browser saves the file instead of showing it in a tab. */
    @Override
    public PresignedLink presignGet(String key, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentType(JSON)
                .responseContentDisposition("attachment; filename=\"trustledger-data-export.json\"")
                .build();
        try {
            PresignedGetObjectRequest presigned = presigner.presignGetObject(
                    GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build());
            return new PresignedLink(presigned.url().toURI(), presigned.expiration());
        } catch (SdkException | URISyntaxException e) {
            throw unavailable("sign a link for", key, e);
        }
    }

    /** Local profile and tests only; in AWS the bucket comes from Terraform (M14). */
    void createBucketIfMissing() {
        try {
            s3.createBucket(b -> b.bucket(bucket));
            log.info("Created data export bucket {}", bucket);
        } catch (BucketAlreadyOwnedByYouException exists) {
            log.debug("Data export bucket {} already exists", bucket);
        }
    }

    @Override
    public void destroy() {
        presigner.close();
        s3.close();
    }

    /** The key holds only IDs (user and request), so it may be logged. */
    private static TrustLedgerException unavailable(String action, String key, Exception cause) {
        log.warn("Could not {} data export object {}", action, key, cause);
        return new TrustLedgerException(ErrorCode.STORAGE_UNAVAILABLE,
                "Export storage is unavailable. Please try again later.");
    }

    private static AwsCredentialsProvider credentials(PrivacyStorageProperties properties) {
        if (properties.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
