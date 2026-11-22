package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.CaseEvent;
import com.trustledger.aml.cases.domain.CaseEventRepository;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Spring Data implementation of {@link CaseEventRepository}; the bare {@link Repository}, so no delete. */
public interface JpaCaseEventRepository extends Repository<CaseEvent, UUID>, CaseEventRepository {
}
