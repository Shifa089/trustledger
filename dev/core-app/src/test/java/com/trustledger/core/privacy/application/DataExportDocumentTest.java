package com.trustledger.core.privacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.identity.PersonalDataExport;
import com.trustledger.core.identity.UserView;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycStatusSummary;
import com.trustledger.core.payments.TopupRecord;
import com.trustledger.core.payments.TransferRecord;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataExportDocumentTest {

    private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private static final UUID MINE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID CASE = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

    private static final PersonalDataExport PERSON = new PersonalDataExport(USER, "customer.one@example.test",
            "9876543210", "Test Customer One", UserView.Status.ACTIVE, T0,
            List.of(new PersonalDataExport.ConsentRecord("TERMS", "2026-11-01", true, T0, "127.0.0.1")));
    private static final KycStatusSummary KYC = new KycStatusSummary("MIN", List.of(
            new KycStatusSummary.Case(CASE, KycLevel.MIN, "APPROVED", T0, null, T0, null, null)));

    @Test
    void mapsEveryPartWithAmountsAsDecimalStrings() {
        WalletView wallet = new WalletView(MINE, USER, WalletTier.FULL, WalletStatus.ACTIVE, inr(150_050),
                inr(50_000), inr(100_050));
        TransferRecord sent = new TransferRecord(UUID.randomUUID(), MINE, OTHER, inr(10_000), "COMPLETED", "Rent",
                T0, T0);
        TransferRecord received = new TransferRecord(UUID.randomUUID(), OTHER, MINE, inr(2_550), "COMPLETED", null,
                T0, T0);
        TopupRecord topup = new TopupRecord(UUID.randomUUID(), MINE, inr(100_000), "CAPTURED", "order_X", "pay_X",
                T0, T0);

        DataExportDocument doc = DataExportDocument.of(REQUEST, T0, PERSON, KYC, wallet, List.of(sent, received),
                List.of(topup));

        assertThat(doc.format()).isEqualTo("trustledger-data-export/v1");
        assertThat(doc.requestId()).isEqualTo(REQUEST);
        assertThat(doc.userId()).isEqualTo(USER);
        assertThat(doc.generatedAt()).isEqualTo(T0);
        assertThat(doc.profile()).isEqualTo(new DataExportDocument.Profile("customer.one@example.test", "9876543210",
                "Test Customer One", "ACTIVE", T0));
        assertThat(doc.consents()).containsExactly(
                new DataExportDocument.Consent("TERMS", "2026-11-01", true, T0, "127.0.0.1"));
        assertThat(doc.kyc()).isEqualTo(new DataExportDocument.Kyc("MIN", List.of(
                new DataExportDocument.KycCase(CASE, "MIN", "APPROVED", T0, null, T0, null, null))));
        assertThat(doc.wallet()).isEqualTo(new DataExportDocument.Wallet(MINE, "FULL", "ACTIVE", "INR", "1500.50",
                "500.00", "1000.50"));
        assertThat(doc.transfers()).extracting(DataExportDocument.Transfer::direction,
                        DataExportDocument.Transfer::counterpartyWalletId, DataExportDocument.Transfer::amount,
                        DataExportDocument.Transfer::note)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SENT", OTHER, "100.00", "Rent"),
                        org.assertj.core.groups.Tuple.tuple("RECEIVED", OTHER, "25.50", null));
        assertThat(doc.topups()).containsExactly(new DataExportDocument.Topup(topup.topupId(), "1000.00", "INR",
                "CAPTURED", "order_X", "pay_X", T0, T0));
    }

    @Test
    void aUserWithoutAWalletHasNoWalletAndNoMoneyMovements() {
        DataExportDocument doc = DataExportDocument.of(REQUEST, T0, PERSON,
                new KycStatusSummary(KycStatusSummary.NONE, List.of()), null, List.of(), List.of());

        assertThat(doc.wallet()).isNull();
        assertThat(doc.transfers()).isEmpty();
        assertThat(doc.topups()).isEmpty();
        assertThat(doc.kyc().currentLevel()).isEqualTo("NONE");
    }

    @Test
    void toStringPrintsNoPersonalData() {
        DataExportDocument doc = DataExportDocument.of(REQUEST, T0, PERSON, KYC, null, List.of(), List.of());

        assertThat(doc.toString()).contains(REQUEST.toString())
                .doesNotContain("customer.one").doesNotContain("9876543210").doesNotContain("Test Customer")
                .doesNotContain("127.0.0.1");
    }

    @Test
    void thePersonalDataRecordMasksItselfToo() {
        assertThat(PERSON.toString()).doesNotContain("customer.one@").doesNotContain("9876543210")
                .doesNotContain("Test Customer One").doesNotContain("127.0.0.1").contains("3210");
    }

    @Test
    void aTransferNoteIsNeverPrinted() {
        TransferRecord record = new TransferRecord(UUID.randomUUID(), MINE, OTHER, inr(100), "COMPLETED",
                "PAN ABCPE1234F", T0, T0);

        assertThat(record.toString()).doesNotContain("ABCPE1234F");
    }

    private static Money inr(long minor) {
        return Money.of(minor, Money.INR);
    }
}
