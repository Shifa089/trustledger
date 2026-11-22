package com.trustledger.aml.rules.application;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.rules.domain.Severity;
import java.util.UUID;

/** An alert the {@link RuleEngine} just raised; a HIGH one has also written its freeze request (FR-AML-3). */
public record RaisedAlert(UUID alertId, UUID caseId, UUID walletId, String ruleCode, Severity severity) {

    static RaisedAlert of(Alert alert) {
        return new RaisedAlert(alert.getId(), alert.getCaseId(), alert.getWalletId(), alert.getRuleCode(),
                alert.getSeverity());
    }
}
