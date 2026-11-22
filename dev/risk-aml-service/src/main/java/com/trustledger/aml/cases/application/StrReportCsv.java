package com.trustledger.aml.cases.application;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AmlCase;
import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.shared.Amounts;
import com.trustledger.aml.shared.IdMasking;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The STR (suspicious transaction report) export of an escalated case (FR-AML-4) as RFC 4180 CSV: one table, one
 * row per CASE, ALERT and FACT, so it opens in any spreadsheet and loads into any tool.
 * <ul>
 * <li><b>Masked identifiers:</b> wallet, counterparty, reference and officer IDs keep only their last 4 characters
 * ({@link IdMasking}); IDs quoted inside alert details and notes are masked too. The case ID stays whole: it is the
 * report's own reference, the one an analyst quotes back to us, and it identifies no person or account.</li>
 * <li><b>No formula injection:</b> a cell starting with {@code = + - @}, a tab or a carriage return gets a leading
 * {@code '}, so a spreadsheet shows it as text instead of running it (OWASP "CSV injection"). Notes are free text
 * an officer typed.</li>
 * <li>Cells with a comma, quote or line break are quoted, quotes doubled; lines end with CRLF.</li>
 * </ul>
 * Pure: no I/O, no clock, so it is unit-tested line by line.
 */
final class StrReportCsv {

    static final List<String> HEADER = List.of("record_type", "case_id", "case_status", "wallet", "officer",
            "occurred_at", "rule_code", "severity", "direction", "source", "counterparty", "reference", "amount",
            "currency", "details");

    private static final String CRLF = "\r\n";

    private StrReportCsv() {
    }

    static String render(AmlCase amlCase, List<Alert> alerts, List<TransactionFact> facts) {
        StringBuilder csv = new StringBuilder();
        line(csv, HEADER);
        String caseId = amlCase.getId().toString();
        String status = amlCase.getStatus().name();
        line(csv, row("CASE", caseId, status, IdMasking.id(amlCase.getWalletId()),
                IdMasking.id(amlCase.getAssignedTo()), instant(amlCase.getCreatedAt()), "", "", "", "", "", "", "", "",
                IdMasking.idsIn(amlCase.getResolution())));
        for (Alert alert : alerts) {
            line(csv, row("ALERT", caseId, status, IdMasking.id(alert.getWalletId()), "",
                    instant(alert.getCreatedAt()), alert.getRuleCode(), alert.getSeverity().name(), "", "", "", "", "",
                    "", IdMasking.idsIn(alert.getDetails())));
        }
        for (TransactionFact fact : facts) {
            line(csv, row("FACT", caseId, status, IdMasking.id(fact.getWalletId()), "",
                    instant(fact.getOccurredAt()), "", "", fact.getDirection().name(), fact.getSource().name(),
                    IdMasking.id(fact.getCounterpartyWalletId()), IdMasking.id(fact.getReferenceId()),
                    Amounts.decimal(fact.getAmountMinor()), fact.getCurrency(), ""));
        }
        return csv.toString();
    }

    private static List<String> row(String... cells) {
        List<String> row = new ArrayList<>(List.of(cells));
        if (row.size() != HEADER.size()) {
            throw new IllegalStateException("A row needs " + HEADER.size() + " cells");
        }
        return row;
    }

    private static void line(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(cell(cells.get(i)));
        }
        csv.append(CRLF);
    }

    /** One cell: neutralised against formulas, then quoted if needed. */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value;
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private static String instant(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
