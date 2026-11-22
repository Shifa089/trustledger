package com.trustledger.core.privacy.application;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions around an export: read the request, then record the outcome. Kept apart from the S3
 * upload so no database connection or row lock is held during the remote call. Recording an outcome on a request
 * that is no longer PENDING (another worker finished it) changes nothing and returns {@code false}.
 */
@Component
class DataRequestWriter {

    private final DataRequestRepository requests;
    private final Clock clock;

    DataRequestWriter(DataRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<DataRequest> find(UUID requestId) {
        return requests.findById(requestId);
    }

    @Transactional
    public boolean completeExport(UUID requestId) {
        return requests.findById(requestId)
                .filter(DataRequest::isPending)
                .map(request -> {
                    request.completeExport(clock.instant());
                    requests.saveAndFlush(request);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean fail(UUID requestId, String reason) {
        return requests.findById(requestId)
                .filter(DataRequest::isPending)
                .map(request -> {
                    request.fail(reason, clock.instant());
                    requests.saveAndFlush(request);
                    return true;
                })
                .orElse(false);
    }
}
