package com.trustledger.core.kyc.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.kyc.domain.DocumentStorage;
import com.trustledger.core.kyc.domain.DocumentStorage.PresignedLink;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** The S3 adapter against LocalStack: encryption, checksum, delete and presigned links. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class S3DocumentStorageIT {

    private static final String BUCKET = TestcontainersConfiguration.KYC_BUCKET;
    private static final byte[] CONTENT = "%PDF-1.7\nstorage adapter test".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    DocumentStorage storage;

    @Autowired
    S3Client s3;

    @Test
    void storesTheObjectEncryptedWithItsContentType() throws Exception {
        String key = key();

        storage.put(key, CONTENT, "application/pdf", sha256(CONTENT));

        HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key(key));
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(head.contentType()).isEqualTo("application/pdf");
        assertThat(head.contentLength()).isEqualTo(CONTENT.length);
        assertThat(s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray()).isEqualTo(CONTENT);
    }

    @Test
    void aChecksumThatDoesNotMatchTheBytesIsRefusedAndNothingIsStored() {
        String key = key();
        String wrongSha = sha256("something else".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> storage.put(key, CONTENT, "application/pdf", wrongSha))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(BUCKET).key(key))).isInstanceOf(Exception.class);
    }

    @Test
    void deleteRemovesTheObjectAndAMissingKeyIsNotAnError() throws Exception {
        String key = key();
        storage.put(key, CONTENT, "application/pdf", sha256(CONTENT));

        storage.delete(key);

        assertThatThrownBy(() -> s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)))
                .isInstanceOf(NoSuchKeyException.class);
        assertThatCode(() -> storage.delete(key())).doesNotThrowAnyException();
    }

    @Test
    void presignedLinkDownloadsTheBytesAsAnAttachmentUntilItExpires() throws Exception {
        String key = key();
        storage.put(key, CONTENT, "application/pdf", sha256(CONTENT));

        PresignedLink link = storage.presignGet(key, "application/pdf", Duration.ofMinutes(5));

        assertThat(link.url().getQuery()).contains("X-Amz-Expires=300");
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(link.url()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(CONTENT);
            assertThat(response.headers().firstValue("Content-Disposition")).hasValue("attachment");
        }
    }

    @Test
    void anExpiredLinkIsRefused() throws Exception {
        String key = key();
        storage.put(key, CONTENT, "application/pdf", sha256(CONTENT));
        PresignedLink link = storage.presignGet(key, "application/pdf", Duration.ofSeconds(1));

        Thread.sleep(2_500);

        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(link.url()).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(403);
        }
    }

    private static String key() {
        return "kyc/storage-it/" + UUID.randomUUID();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
