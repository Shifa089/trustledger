package com.trustledger.core.privacy.application;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import com.trustledger.core.privacy.domain.DataRequestStatus;
import com.trustledger.core.privacy.domain.DataRequestType;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code POST /me/data-export} (FR-PR-2): records the request; the file is built asynchronously. */
@Service
public class DataExportService {

    private final DataRequestRepository requests;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DataExportService(DataRequestRepository requests, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Stores a PENDING export request and publishes {@link DataExportRequested} in the same transaction, so the
     * request and its processing either both happen or neither does (the outbox, ADR 0005).
     *
     * @throws TrustLedgerException {@code REQUEST_IN_PROGRESS} if the user already has an export in progress
     *                              (checked here, and by the partial unique index for a racing second request)
     */
    @Transactional
    public DataRequestView requestExport(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        if (requests.existsByUserIdAndTypeAndStatus(userId, DataRequestType.EXPORT, DataRequestStatus.PENDING)) {
            throw inProgress();
        }
        DataRequest request;
        try {
            request = requests.saveAndFlush(DataRequest.export(userId, clock.instant()));
        } catch (DataIntegrityViolationException raceLost) {
            throw inProgress();
        }
        events.publishEvent(new DataExportRequested(request.getId(), userId));
        return DataRequestView.of(request, null);
    }

    private static TrustLedgerException inProgress() {
        return new TrustLedgerException(ErrorCode.REQUEST_IN_PROGRESS,
                "A data export is already being prepared. Check GET /me/data-requests for its status.");
    }
}
