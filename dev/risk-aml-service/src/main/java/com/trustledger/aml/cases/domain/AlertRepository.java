package com.trustledger.aml.cases.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Port for {@code aml.alerts}. Append-only: no update, no delete. */
public interface AlertRepository {

    Alert save(Alert alert);

    /** Has this rule already alerted for this wallet since {@code since}? (repeat suppression) */
    boolean existsByWalletIdAndRuleCodeAndCreatedAtGreaterThanEqual(UUID walletId, String ruleCode, Instant since);

    List<Alert> findByCaseIdOrderByCreatedAtAsc(UUID caseId);

    /** The alerts of one page of cases, for the counts in the case list. */
    List<Alert> findByCaseIdIn(Collection<UUID> caseIds);
}
