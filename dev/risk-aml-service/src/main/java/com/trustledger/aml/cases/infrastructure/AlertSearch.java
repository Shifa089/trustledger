package com.trustledger.aml.cases.infrastructure;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Spring Data fragment behind {@code AlertRepository.search}: three optional filters, built as a Criteria query in
 * {@link AlertSearchImpl}. A fragment rather than {@code JpaSpecificationExecutor}, which would also bring
 * {@code delete(Specification)} to an append-only table.
 */
public interface AlertSearch {

    Page<Alert> search(AlertFilter filter, Pageable pageable);
}
