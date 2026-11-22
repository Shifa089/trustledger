package com.trustledger.core.privacy.infrastructure;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.DataRequestRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of {@link DataRequestRepository}. */
public interface JpaDataRequestRepository extends JpaRepository<DataRequest, UUID>, DataRequestRepository {
}
