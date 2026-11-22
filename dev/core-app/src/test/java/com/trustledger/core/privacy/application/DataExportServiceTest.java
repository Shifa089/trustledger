package com.trustledger.core.privacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import com.trustledger.core.privacy.domain.DataRequestStatus;
import com.trustledger.core.privacy.domain.DataRequestType;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class DataExportServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final DataRequestRepository requests = mock(DataRequestRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DataExportService service =
            new DataExportService(requests, events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storesAPendingRequestAndPublishesTheEvent() {
        when(requests.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0)));

        DataRequestView view = service.requestExport(USER);

        assertThat(view.id()).isEqualTo(REQUEST);
        assertThat(view.type()).isEqualTo(DataRequestType.EXPORT);
        assertThat(view.status()).isEqualTo(DataRequestStatus.PENDING);
        assertThat(view.requestedAt()).isEqualTo(NOW);
        assertThat(view.completedAt()).isNull();
        assertThat(view.download()).isNull();
        verify(events).publishEvent(new DataExportRequested(REQUEST, USER));
    }

    @Test
    void anExportInProgressIsRefused() {
        when(requests.existsByUserIdAndTypeAndStatus(USER, DataRequestType.EXPORT, DataRequestStatus.PENDING))
                .thenReturn(true);

        assertThatThrownBy(() -> service.requestExport(USER)).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS));
        verify(requests, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aRacingSecondRequestStoppedByTheUniqueIndexIsRefusedTheSameWay() {
        when(requests.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_data_requests_one_pending"));

        assertThatThrownBy(() -> service.requestExport(USER)).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REQUEST_IN_PROGRESS));
        verify(events, never()).publishEvent(any(Object.class));
    }

    private static DataRequest withId(DataRequest request) {
        try {
            Field field = DataRequest.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(request, REQUEST);
            return request;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
