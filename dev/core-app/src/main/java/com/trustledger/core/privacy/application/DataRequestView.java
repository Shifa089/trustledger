package com.trustledger.core.privacy.application;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestStatus;
import com.trustledger.core.privacy.domain.DataRequestType;
import com.trustledger.core.privacy.domain.ExportStorage.PresignedLink;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A data request as its owner sees it. {@code download} is set only for a completed export: a presigned link
 * made for this response. The failure reason stays internal (it is a code for operators, not for customers).
 */
public record DataRequestView(UUID id, DataRequestType type, DataRequestStatus status, Instant requestedAt,
        Instant completedAt, Download download) {

    /** A short-lived link to the export file; ask again for a fresh one after {@code expiresAt}. */
    public record Download(URI url, Instant expiresAt) {

        static Download of(PresignedLink link) {
            return link == null ? null : new Download(link.url(), link.expiresAt());
        }
    }

    static DataRequestView of(DataRequest request, PresignedLink link) {
        return new DataRequestView(request.getId(), request.getType(), request.getStatus(), request.getRequestedAt(),
                request.getCompletedAt(), Download.of(link));
    }
}
