package com.trustledger.aml.cases.domain;

import java.util.List;
import java.util.UUID;

/** Port for {@code aml.freeze_requests}: insert and read. Sending is {@link PendingFreezeRequests}' job. */
public interface FreezeRequestRepository {

    FreezeRequest save(FreezeRequest request);

    List<FreezeRequest> findByCaseIdOrderByRequestedAtAsc(UUID caseId);
}
