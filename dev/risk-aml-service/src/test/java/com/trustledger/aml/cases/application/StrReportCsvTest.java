package com.trustledger.aml.cases.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AmlCase;
import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.rules.domain.Severity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StrReportCsvTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final UUID CASE = UUID.fromString("0193a000-0000-7000-8000-00000000ca5e");
    private static final UUID WALLET = UUID.fromString("0193a000-0000-7000-8000-00000000aaa1");
    private static final UUID RECEIVER = UUID.fromString("0193a000-0000-7000-8000-00000000bbb2");
    private static final UUID TRANSFER = UUID.fromString("0193a000-0000-7000-8000-00000000c0c3");
    private static final UUID OFFICER = UUID.fromString("0193a000-0000-7000-8000-00000000d0d4");

    @Test
    void oneHeaderAndOneRowPerCaseAlertAndFactWithCrlf() {
        String csv = StrReportCsv.render(escalated("STR filed"), List.of(alert("3 transactions")), List.of(fact()));

        String[] lines = csv.split("\r\n", -1);
        assertThat(lines).hasSize(5); // header, CASE, ALERT, FACT, and the empty string after the last CRLF
        assertThat(lines[0]).isEqualTo(String.join(",", StrReportCsv.HEADER));
        assertThat(lines[1]).isEqualTo(
                "CASE," + CASE + ",ESCALATED,****aaa1,****d0d4,2026-11-22T09:00:00Z,,,,,,,,,STR filed");
        assertThat(lines[2]).isEqualTo(
                "ALERT," + CASE + ",ESCALATED,****aaa1,,2026-11-22T09:30:00Z,R2,HIGH,,,,,,,3 transactions");
        assertThat(lines[3]).isEqualTo(
                "FACT," + CASE + ",ESCALATED,****aaa1,,2026-11-22T08:00:00Z,,,OUT,TRANSFER,****bbb2,****c0c3,"
                        + "46000.00,INR,");
        assertThat(lines[4]).isEmpty();
    }

    @Test
    void noFullWalletTransferOrOfficerIdLeavesTheReport() {
        String csv = StrReportCsv.render(escalated("Linked to wallet " + RECEIVER),
                List.of(alert("latest transfer " + TRANSFER)), List.of(fact()));

        assertThat(csv).doesNotContain(WALLET.toString(), RECEIVER.toString(), TRANSFER.toString(),
                OFFICER.toString());
        assertThat(csv).contains("Linked to wallet ****bbb2", "latest transfer ****c0c3");
        assertThat(csv).contains(CASE.toString()); // the report's own reference stays whole
    }

    @Test
    void formulasAreNeutralised() {
        assertThat(StrReportCsv.cell("=HYPERLINK(\"http://evil\")")).isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(StrReportCsv.cell("+1")).isEqualTo("'+1");
        assertThat(StrReportCsv.cell("-1")).isEqualTo("'-1");
        assertThat(StrReportCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(StrReportCsv.cell("\tx")).isEqualTo("'\tx");
        assertThat(StrReportCsv.cell("plain")).isEqualTo("plain");
    }

    @Test
    void commasQuotesAndLineBreaksAreQuoted() {
        assertThat(StrReportCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(StrReportCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(StrReportCsv.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(StrReportCsv.cell("")).isEmpty();
        assertThat(StrReportCsv.cell(null)).isEmpty();
    }

    @Test
    void notesWithAFormulaAndACommaStayOneCell() {
        String csv = StrReportCsv.render(escalated("=1+1, then more"), List.of(), List.of());

        assertThat(csv.split("\r\n")[1]).endsWith(",\"'=1+1, then more\"");
    }

    private static AmlCase escalated(String notes) {
        AmlCase amlCase = AmlCase.open(WALLET, NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(amlCase, "id", CASE);
        amlCase.assign(OFFICER, NOW);
        amlCase.escalate(OFFICER, notes, NOW);
        return amlCase;
    }

    private static Alert alert(String details) {
        AmlCase open = AmlCase.open(WALLET, NOW);
        ReflectionTestUtils.setField(open, "id", CASE);
        return Alert.raise(open, "R2", Severity.HIGH, details, NOW.minusSeconds(1800));
    }

    private static TransactionFact fact() {
        return TransactionFact.outgoingTransfer(UUID.randomUUID(), WALLET, RECEIVER, TRANSFER, 4_600_000, "INR",
                NOW.minusSeconds(7200), NOW);
    }
}
