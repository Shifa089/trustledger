package com.trustledger.core.privacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import com.trustledger.core.privacy.domain.DataRequestStatus;
import com.trustledger.core.privacy.domain.ExportStorage;
import com.trustledger.core.privacy.domain.ExportStorage.PresignedLink;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

class DataRequestQueryServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    private final DataRequestRepository requests = mock(DataRequestRepository.class);
    private final ExportStorage storage = mock(ExportStorage.class);
    private final DataRequestQueryService service =
            new DataRequestQueryService(requests, storage, new PrivacyProperties(TTL, 7));

    @Test
    void onlyACompletedExportGetsAFreshLinkWithTheConfiguredLifetime() {
        DataRequest completed = request(UUID.fromString("00000000-0000-7000-8000-000000000003"));
        completed.completeExport(T0.plusSeconds(2));
        DataRequest failed = request(UUID.fromString("00000000-0000-7000-8000-000000000002"));
        failed.fail("STORAGE_UNAVAILABLE", T0);
        DataRequest pending = request(UUID.fromString("00000000-0000-7000-8000-000000000001"));
        when(requests.findByUserIdOrderByCreatedAtDescIdDesc(USER, Limit.of(7)))
                .thenReturn(List.of(completed, failed, pending));
        PresignedLink link = new PresignedLink(URI.create("https://s3.example.test/x?X-Amz-Signature=abc"),
                T0.plus(TTL));
        when(storage.presignGet(completed.getResultS3Key(), TTL)).thenReturn(link);

        List<DataRequestView> views = service.list(USER);

        assertThat(views).extracting(DataRequestView::status).containsExactly(DataRequestStatus.COMPLETED,
                DataRequestStatus.FAILED, DataRequestStatus.PENDING);
        assertThat(views.get(0).download()).isEqualTo(new DataRequestView.Download(link.url(), link.expiresAt()));
        assertThat(views.get(0).completedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(views.get(1).download()).isNull();
        assertThat(views.get(2).download()).isNull();
        verify(storage).presignGet(completed.getResultS3Key(), TTL);
    }

    @Test
    void noRequestsNoLinks() {
        when(requests.findByUserIdOrderByCreatedAtDescIdDesc(USER, Limit.of(7))).thenReturn(List.of());

        assertThat(service.list(USER)).isEmpty();
        verify(storage, never()).presignGet(anyString(), any());
    }

    private static DataRequest request(UUID id) {
        DataRequest request = DataRequest.export(USER, T0);
        try {
            Field field = DataRequest.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(request, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return request;
    }
}
