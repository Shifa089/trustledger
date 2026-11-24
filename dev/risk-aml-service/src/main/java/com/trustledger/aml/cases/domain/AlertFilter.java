package com.trustledger.aml.cases.domain;

import com.trustledger.aml.rules.domain.Severity;
import java.util.UUID;

/**
 * The optional filters of the alert list (SPEC §5 {@code GET /aml/alerts}: rule, severity, wallet). A {@code null}
 * field does not filter; all non-null fields must match.
 */
public record AlertFilter(String ruleCode, Severity severity, UUID walletId) {

    public static final AlertFilter NONE = new AlertFilter(null, null, null);
}
