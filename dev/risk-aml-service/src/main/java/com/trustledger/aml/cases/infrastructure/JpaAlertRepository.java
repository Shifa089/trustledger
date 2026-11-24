package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertRepository;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Spring Data implementation of {@link AlertRepository}; the bare {@link Repository}, so no delete. The alert list's
 * {@code search} comes from the {@link AlertSearch} fragment.
 */
public interface JpaAlertRepository extends Repository<Alert, UUID>, AlertRepository, AlertSearch {
}
