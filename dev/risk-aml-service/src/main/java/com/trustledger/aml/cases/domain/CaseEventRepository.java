package com.trustledger.aml.cases.domain;

import java.util.List;
import java.util.UUID;

/** Port for {@code aml.case_events}: insert and read. Sending is the job of {@link PendingCaseEvents}. */
public interface CaseEventRepository {

    CaseEvent save(CaseEvent event);

    List<CaseEvent> findByCaseIdOrderByOccurredAtAsc(UUID caseId);
}
