package com.trustledger.core.privacy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;

/** Port for data requests; implemented by Spring Data in {@code privacy.infrastructure}. */
public interface DataRequestRepository {

    /**
     * Flushes at once, so a second PENDING request of the same type (partial unique index
     * {@code uk_data_requests_one_pending}) surfaces here and not at commit.
     */
    DataRequest saveAndFlush(DataRequest request);

    Optional<DataRequest> findById(UUID id);

    boolean existsByUserIdAndTypeAndStatus(UUID userId, DataRequestType type, DataRequestStatus status);

    /** The user's requests, newest first, at most {@code limit}. */
    List<DataRequest> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Limit limit);
}
