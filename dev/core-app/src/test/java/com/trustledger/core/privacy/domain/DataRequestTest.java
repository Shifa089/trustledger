package com.trustledger.core.privacy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataRequestTest {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void newExportIsPendingWithoutResult() {
        DataRequest request = DataRequest.export(USER, T0);

        assertThat(request.getType()).isEqualTo(DataRequestType.EXPORT);
        assertThat(request.getStatus()).isEqualTo(DataRequestStatus.PENDING);
        assertThat(request.isPending()).isTrue();
        assertThat(request.hasDownload()).isFalse();
        assertThat(request.getRequestedAt()).isEqualTo(T0);
        assertThat(request.getResultS3Key()).isNull();
        assertThat(request.getCompletedAt()).isNull();
    }

    @Test
    void exportKeyIsDeterministicPerUserAndRequest() {
        DataRequest request = saved(DataRequest.export(USER, T0));

        assertThat(request.exportKey()).isEqualTo("exports/" + USER + "/" + ID + ".json");
        assertThat(request.exportKey()).isEqualTo(request.exportKey());
    }

    @Test
    void exportKeyNeedsAnId() {
        assertThatThrownBy(() -> DataRequest.export(USER, T0).exportKey()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void completingStoresTheKeyAndTime() {
        DataRequest request = saved(DataRequest.export(USER, T0));

        request.completeExport(T0.plusSeconds(3));

        assertThat(request.getStatus()).isEqualTo(DataRequestStatus.COMPLETED);
        assertThat(request.getResultS3Key()).isEqualTo(request.exportKey());
        assertThat(request.getCompletedAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(request.getUpdatedAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(request.hasDownload()).isTrue();
        assertThat(request.getFailureReason()).isNull();
    }

    @Test
    void failingStoresTheReasonAndNoKey() {
        DataRequest request = saved(DataRequest.export(USER, T0));

        request.fail("STORAGE_UNAVAILABLE", T0.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(DataRequestStatus.FAILED);
        assertThat(request.getFailureReason()).isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(request.getResultS3Key()).isNull();
        assertThat(request.getCompletedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(request.hasDownload()).isFalse();
    }

    @Test
    void longReasonsAreCutToTheColumnSize() {
        DataRequest request = saved(DataRequest.export(USER, T0));

        request.fail("X".repeat(200), T0);

        assertThat(request.getFailureReason()).hasSize(DataRequest.MAX_REASON_LENGTH);
    }

    @Test
    void blankReasonIsRefused() {
        DataRequest request = saved(DataRequest.export(USER, T0));

        assertThatThrownBy(() -> request.fail(" ", T0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.isPending()).isTrue();
    }

    @Test
    void aFinishedRequestCannotChangeAgain() {
        DataRequest completed = saved(DataRequest.export(USER, T0));
        completed.completeExport(T0);
        DataRequest failed = saved(DataRequest.export(USER, T0));
        failed.fail("NOT_FOUND", T0);

        assertInvalid(() -> completed.completeExport(T0));
        assertInvalid(() -> completed.fail("NOT_FOUND", T0));
        assertInvalid(() -> failed.completeExport(T0));
        assertInvalid(() -> failed.fail("NOT_FOUND", T0));
        assertThat(completed.getStatus()).isEqualTo(DataRequestStatus.COMPLETED);
        assertThat(failed.getStatus()).isEqualTo(DataRequestStatus.FAILED);
    }

    @Test
    void aClockThatWentBackwardsNeverFinishesBeforeTheRequest() {
        DataRequest request = saved(DataRequest.export(USER, T0));

        request.completeExport(T0.minusSeconds(5));

        assertThat(request.getCompletedAt()).isEqualTo(T0);
    }

    @Test
    void requiredArguments() {
        assertThatThrownBy(() -> DataRequest.export(null, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DataRequest.export(USER, null)).isInstanceOf(NullPointerException.class);
    }

    private static void assertInvalid(Runnable step) {
        assertThatThrownBy(step::run).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    /** As after {@code save}: Hibernate assigns the UUIDv7. */
    static DataRequest saved(DataRequest request) {
        return withId(request, ID);
    }

    static DataRequest withId(DataRequest request, UUID id) {
        try {
            Field field = DataRequest.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(request, id);
            return request;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
