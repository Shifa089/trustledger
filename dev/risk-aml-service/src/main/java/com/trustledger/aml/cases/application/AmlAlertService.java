package com.trustledger.aml.cases.application;

import com.trustledger.aml.cases.application.CaseViews.AlertPage;
import com.trustledger.aml.cases.application.CaseViews.AlertSummary;
import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertFilter;
import com.trustledger.aml.cases.domain.AlertRepository;
import com.trustledger.aml.shared.ErrorCode;
import com.trustledger.aml.rules.domain.Severity;
import com.trustledger.aml.shared.TrustLedgerException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert list for compliance officers (FR-AML-2, SPEC §5 {@code GET /aml/alerts}): every rule hit, newest first,
 * filtered by rule, severity and/or wallet. Read-only; an alert never changes after it is raised.
 * <p>
 * A rule code must look like one ({@code R} and one or two digits, the column is 2 characters wide); a well-formed
 * code no rule uses (say {@code R9}) simply matches nothing.
 */
@Service
public class AmlAlertService {

    private static final Pattern RULE_CODE = Pattern.compile("R[0-9]{1,2}");

    private final AlertRepository alerts;
    private final CaseProperties properties;

    public AmlAlertService(AlertRepository alerts, CaseProperties properties) {
        this.alerts = alerts;
        this.properties = properties;
    }

    /** Each filter is optional ({@code null}); set filters must all match. */
    @Transactional(readOnly = true)
    public AlertPage list(String ruleCode, Severity severity, UUID walletId, int page, int size) {
        if (page < 0 || size < 1 || size > properties.maxPageSize()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "page must be 0 or more and size 1.." + properties.maxPageSize() + ".");
        }
        if (ruleCode != null && !RULE_CODE.matcher(ruleCode).matches()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "ruleCode must look like R1.");
        }
        Page<Alert> result = alerts.search(new AlertFilter(ruleCode, severity, walletId), PageRequest.of(page, size));
        return new AlertPage(result.stream().map(AlertSummary::of).toList(), page, size, result.getTotalElements(),
                result.getTotalPages());
    }
}
