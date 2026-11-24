package com.trustledger.aml.cases.application;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AmlCase;
import com.trustledger.aml.cases.domain.CaseStatus;
import com.trustledger.aml.cases.domain.FreezeRequest;
import com.trustledger.aml.facts.domain.Direction;
import com.trustledger.aml.facts.domain.FactSource;
import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.rules.domain.Severity;
import com.trustledger.aml.shared.Amounts;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The read models of the case endpoints (FR-AML-4), mapped by static {@code of(...)} factories (ADR 0016); entities
 * never leave the service layer. Shown to compliance officers only. Amounts leave as decimal strings.
 */
public final class CaseViews {

    private CaseViews() {
    }

    /** One line of the case list. {@code highestSeverity} is {@code null} only for a case without alerts. */
    public record CaseSummary(UUID id, UUID walletId, CaseStatus status, UUID assignedTo, int alertCount,
            Severity highestSeverity, Instant createdAt, Instant updatedAt) {

        static CaseSummary of(AmlCase amlCase, List<Alert> alerts) {
            Severity highest = alerts.stream().map(Alert::getSeverity).max(Comparator.naturalOrder()).orElse(null);
            return new CaseSummary(amlCase.getId(), amlCase.getWalletId(), amlCase.getStatus(),
                    amlCase.getAssignedTo(), alerts.size(), highest, amlCase.getCreatedAt(), amlCase.getUpdatedAt());
        }
    }

    /** A page of cases, oldest first. */
    public record CasePage(List<CaseSummary> content, int page, int size, long totalElements, int totalPages) {
    }

    /** One line of the alert list ({@code GET /aml/alerts}): the rule hit and the case it joined. */
    public record AlertSummary(UUID id, UUID caseId, UUID walletId, String ruleCode, Severity severity,
            String details, Instant createdAt) {

        static AlertSummary of(Alert alert) {
            return new AlertSummary(alert.getId(), alert.getCaseId(), alert.getWalletId(), alert.getRuleCode(),
                    alert.getSeverity(), alert.getDetails(), alert.getCreatedAt());
        }
    }

    /** A page of alerts, newest first. */
    public record AlertPage(List<AlertSummary> content, int page, int size, long totalElements, int totalPages) {
    }

    /** The case with its evidence: alerts, freeze requests and the wallet's facts around the alerts. */
    public record CaseDetail(UUID id, UUID walletId, CaseStatus status, UUID assignedTo, String resolution,
            Instant createdAt, Instant updatedAt, List<AlertItem> alerts, List<FreezeRequestItem> freezeRequests,
            List<FactItem> facts) {

        static CaseDetail of(AmlCase amlCase, List<Alert> alerts, List<FreezeRequest> freezeRequests,
                List<TransactionFact> facts) {
            return new CaseDetail(amlCase.getId(), amlCase.getWalletId(), amlCase.getStatus(),
                    amlCase.getAssignedTo(), amlCase.getResolution(), amlCase.getCreatedAt(), amlCase.getUpdatedAt(),
                    alerts.stream().map(AlertItem::of).toList(),
                    freezeRequests.stream().map(FreezeRequestItem::of).toList(),
                    facts.stream().map(FactItem::of).toList());
        }
    }

    public record AlertItem(UUID id, String ruleCode, Severity severity, String details, Instant createdAt) {

        static AlertItem of(Alert alert) {
            return new AlertItem(alert.getId(), alert.getRuleCode(), alert.getSeverity(), alert.getDetails(),
                    alert.getCreatedAt());
        }
    }

    /** {@code publishedAt} is empty while the request waits in the outbox (FR-AML-3). */
    public record FreezeRequestItem(UUID id, UUID alertId, String ruleCode, Instant requestedAt, Instant publishedAt) {

        static FreezeRequestItem of(FreezeRequest request) {
            return new FreezeRequestItem(request.getId(), request.getAlertId(), request.getRuleCode(),
                    request.getRequestedAt(), request.getPublishedAt());
        }
    }

    public record FactItem(UUID id, Direction direction, FactSource source, UUID counterpartyWalletId,
            UUID referenceId, String amount, String currency, Instant occurredAt) {

        static FactItem of(TransactionFact fact) {
            return new FactItem(fact.getId(), fact.getDirection(), fact.getSource(), fact.getCounterpartyWalletId(),
                    fact.getReferenceId(), Amounts.decimal(fact.getAmountMinor()), fact.getCurrency(),
                    fact.getOccurredAt());
        }
    }

    /** The STR export: a file name and its CSV text. */
    public record StrReport(String fileName, String csv) {
    }
}
