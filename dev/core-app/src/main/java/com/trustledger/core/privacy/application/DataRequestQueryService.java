package com.trustledger.core.privacy.application;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import com.trustledger.core.privacy.domain.ExportStorage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /me/data-requests} (FR-PR-2): the caller's own requests, newest first. Each completed export gets a
 * <b>fresh</b> presigned link valid {@code trustledger.privacy.export-link-ttl} (10 minutes): the link is never
 * stored, so an old one can't be replayed after it expires. Only the caller's requests are read (no ID in the
 * request, so no BOLA).
 */
@Service
public class DataRequestQueryService {

    private final DataRequestRepository requests;
    private final ExportStorage storage;
    private final Duration linkTtl;
    private final int maxListed;

    DataRequestQueryService(DataRequestRepository requests, ExportStorage storage, PrivacyProperties properties) {
        this.requests = requests;
        this.storage = storage;
        this.linkTtl = properties.exportLinkTtl();
        this.maxListed = properties.maxListedRequests();
    }

    /** Signing a link is a local computation, not a call to S3, so it is fine inside the read transaction. */
    @Transactional(readOnly = true)
    public List<DataRequestView> list(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return requests.findByUserIdOrderByCreatedAtDescIdDesc(userId, Limit.of(maxListed)).stream()
                .map(this::view)
                .toList();
    }

    private DataRequestView view(DataRequest request) {
        return DataRequestView.of(request,
                request.hasDownload() ? storage.presignGet(request.getResultS3Key(), linkTtl) : null);
    }
}
