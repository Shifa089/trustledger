package com.trustledger.aml.cases.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port for {@code aml.alerts}. Append-only: no update, no delete. */
public interface AlertRepository {

    Alert save(Alert alert);

    /** Has this rule already alerted for this wallet since {@code since}? (repeat suppression) */
    boolean existsByWalletIdAndRuleCodeAndCreatedAtGreaterThanEqual(UUID walletId, String ruleCode, Instant since);

    List<Alert> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
