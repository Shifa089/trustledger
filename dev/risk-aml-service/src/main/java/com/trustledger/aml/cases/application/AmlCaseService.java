package com.trustledger.aml.cases.application;

import com.trustledger.aml.cases.application.CaseViews.CaseDetail;
import com.trustledger.aml.cases.application.CaseViews.CasePage;
import com.trustledger.aml.cases.application.CaseViews.CaseSummary;
import com.trustledger.aml.cases.application.CaseViews.StrReport;
import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertRepository;
import com.trustledger.aml.cases.domain.AmlCase;
import com.trustledger.aml.cases.domain.AmlCaseRepository;
import com.trustledger.aml.cases.domain.CaseStatus;
import com.trustledger.aml.cases.domain.FreezeRequestRepository;
import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.facts.domain.TransactionFactRepository;
import com.trustledger.aml.shared.ErrorCode;
import com.trustledger.aml.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The case workflow for compliance officers (FR-AML-4, SPEC §5 "AML service"). The officer comes from the token.
 * The state rules live in {@link AmlCase}; a lost race on the same case is an optimistic-lock 409.
 * <p>
 * Closing a case as a false positive does <b>not</b> unfreeze the wallet: unfreezing is a separate, deliberate
 * officer action in core-app ({@code /compliance/wallets/{id}/unfreeze}, ADR 0007). Notes are never logged: they may
 * quote personal data.
 */
@Service
public class AmlCaseService {

    private static final Logger log = LoggerFactory.getLogger(AmlCaseService.class);

    private final AmlCaseRepository cases;
    private final AlertRepository alerts;
    private final FreezeRequestRepository freezeRequests;
    private final TransactionFactRepository facts;
    private final CaseProperties properties;
    private final Clock clock;

    public AmlCaseService(AmlCaseRepository cases, AlertRepository alerts, FreezeRequestRepository freezeRequests,
            TransactionFactRepository facts, CaseProperties properties, Clock clock) {
        this.cases = cases;
        this.alerts = alerts;
        this.freezeRequests = freezeRequests;
        this.facts = facts;
        this.properties = properties;
        this.clock = clock;
    }

    /** Cases oldest first, optionally by status and/or wallet. */
    @Transactional(readOnly = true)
    public CasePage list(CaseStatus status, UUID walletId, int page, int size) {
        if (page < 0 || size < 1 || size > properties.maxPageSize()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "page must be 0 or more and size 1.." + properties.maxPageSize() + ".");
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<AmlCase> result;
        if (status != null && walletId != null) {
            result = cases.findByStatusAndWalletIdOrderByCreatedAtAscIdAsc(status, walletId, pageable);
        } else if (status != null) {
            result = cases.findByStatusOrderByCreatedAtAscIdAsc(status, pageable);
        } else if (walletId != null) {
            result = cases.findByWalletIdOrderByCreatedAtAscIdAsc(walletId, pageable);
        } else {
            result = cases.findAllByOrderByCreatedAtAscIdAsc(pageable);
        }
        Map<UUID, List<Alert>> alertsByCase = result.isEmpty() ? Map.of()
                : alerts.findByCaseIdIn(result.map(AmlCase::getId).toList()).stream()
                        .collect(Collectors.groupingBy(Alert::getCaseId));
        List<CaseSummary> content = result.stream()
                .map(c -> CaseSummary.of(c, alertsByCase.getOrDefault(c.getId(), List.of())))
                .toList();
        return new CasePage(content, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public CaseDetail get(UUID caseId) {
        return detail(find(caseId));
    }

    /** OPEN → INVESTIGATING, or a reassignment; {@code assigneeId} defaults to the caller. */
    @Transactional
    public CaseDetail assign(UUID caseId, UUID caller, UUID assigneeId) {
        Objects.requireNonNull(caller, "caller");
        AmlCase amlCase = find(caseId);
        UUID assignee = assigneeId == null ? caller : assigneeId;
        amlCase.assign(assignee, clock.instant());
        log.info("AML case assigned case={} assignee={} by={}", caseId, assignee, caller);
        return detail(amlCase);
    }

    @Transactional
    public CaseDetail closeAsFalsePositive(UUID caseId, UUID caller, String notes) {
        AmlCase amlCase = find(caseId);
        amlCase.closeAsFalsePositive(caller, notes, clock.instant());
        log.info("AML case closed as false positive case={} by={}", caseId, caller);
        return detail(amlCase);
    }

    @Transactional
    public CaseDetail escalate(UUID caseId, UUID caller, String notes) {
        AmlCase amlCase = find(caseId);
        amlCase.escalate(caller, notes, clock.instant());
        log.info("AML case escalated case={} by={}", caseId, caller);
        return detail(amlCase);
    }

    /** The STR CSV; only an ESCALATED case has one (an STR is filed only for an escalation). */
    @Transactional(readOnly = true)
    public StrReport strReport(UUID caseId) {
        AmlCase amlCase = find(caseId);
        if (amlCase.getStatus() != CaseStatus.ESCALATED) {
            throw new TrustLedgerException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only an escalated case has an STR report.");
        }
        List<Alert> caseAlerts = alerts.findByCaseIdOrderByCreatedAtAsc(caseId);
        String csv = StrReportCsv.render(amlCase, caseAlerts, factsAround(amlCase, caseAlerts));
        log.info("STR report exported case={}", caseId);
        return new StrReport("str-" + caseId + ".csv", csv);
    }

    private CaseDetail detail(AmlCase amlCase) {
        List<Alert> caseAlerts = alerts.findByCaseIdOrderByCreatedAtAsc(amlCase.getId());
        return CaseDetail.of(amlCase, caseAlerts, freezeRequests.findByCaseIdOrderByRequestedAtAsc(amlCase.getId()),
                factsAround(amlCase, caseAlerts));
    }

    /**
     * The wallet's facts from the first alert minus the lookback (the longest rule window) up to the last alert:
     * everything a rule on this case can have counted.
     */
    private List<TransactionFact> factsAround(AmlCase amlCase, List<Alert> caseAlerts) {
        if (caseAlerts.isEmpty()) {
            return List.of();
        }
        Instant from = caseAlerts.getFirst().getCreatedAt().minus(properties.factLookback());
        Instant to = caseAlerts.getLast().getCreatedAt();
        return facts.findByWalletIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(amlCase.getWalletId(), from, to,
                Limit.of(properties.maxFacts()));
    }

    /** An unknown case is 404 (every officer may see every case, so there is nothing to hide). */
    private AmlCase find(UUID caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Case not found."));
    }
}
