package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.FreezeRequest;
import com.trustledger.aml.cases.domain.FreezeRequestRepository;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Spring Data implementation of {@link FreezeRequestRepository}; the bare {@link Repository}, so no delete. */
public interface JpaFreezeRequestRepository extends Repository<FreezeRequest, UUID>, FreezeRequestRepository {
}
