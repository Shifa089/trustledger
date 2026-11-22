package com.trustledger.core.privacy.application;

import com.trustledger.core.identity.PersonalDataExport;
import com.trustledger.core.kyc.KycStatusSummary;
import com.trustledger.core.payments.TopupRecord;
import com.trustledger.core.payments.TransferRecord;
import com.trustledger.core.wallet.WalletView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The JSON file a customer downloads (FR-PR-2), format {@value #FORMAT}. Amounts are decimal strings in rupees
 * (SPEC §6), times are UTC. It holds the user's personal data <b>in clear</b>: it is written to S3 and nowhere
 * else, and {@link #toString()} prints no field of it.
 * <p>
 * What is deliberately not in it: KYC documents, PAN, date of birth and address (the export carries KYC status
 * only), anything about counterparties beyond their wallet ID, officers' identities, and internal IDs such as
 * journal entries.
 *
 * @param wallet {@code null} for a user without a wallet (no KYC yet); then transfers and top-ups are empty
 */
public record DataExportDocument(String format, UUID requestId, UUID userId, Instant generatedAt, Profile profile,
        List<Consent> consents, Kyc kyc, Wallet wallet, List<Transfer> transfers, List<Topup> topups) {

    public static final String FORMAT = "trustledger-data-export/v1";

    public record Profile(String email, String phone, String fullName, String status, Instant registeredAt) {
    }

    public record Consent(String purpose, String policyVersion, boolean granted, Instant capturedAt, String ip) {
    }

    public record Kyc(String currentLevel, List<KycCase> cases) {
    }

    public record KycCase(UUID caseId, String level, String status, Instant submittedAt, Instant verifiedAt,
            Instant approvedAt, Instant rejectedAt, String rejectionReason) {
    }

    public record Wallet(UUID walletId, String tier, String status, String currency, String balance, String held,
            String available) {
    }

    /** {@code direction}: SENT or RECEIVED, seen from this user's wallet. */
    public record Transfer(UUID transferId, String direction, UUID counterpartyWalletId, String amount,
            String currency, String status, String note, Instant createdAt, Instant updatedAt) {
    }

    public record Topup(UUID topupId, String amount, String currency, String status, String gatewayOrderId,
            String gatewayPaymentId, Instant createdAt, Instant updatedAt) {
    }

    static DataExportDocument of(UUID requestId, Instant generatedAt, PersonalDataExport person, KycStatusSummary kyc,
            WalletView wallet, List<TransferRecord> transfers, List<TopupRecord> topups) {
        return new DataExportDocument(FORMAT, requestId, person.userId(), generatedAt,
                new Profile(person.email(), person.phone(), person.fullName(), person.status().name(),
                        person.registeredAt()),
                person.consents().stream()
                        .map(c -> new Consent(c.purpose(), c.policyVersion(), c.granted(), c.capturedAt(), c.ip()))
                        .toList(),
                new Kyc(kyc.currentLevel(), kyc.cases().stream()
                        .map(c -> new KycCase(c.caseId(), c.level().name(), c.status(), c.submittedAt(),
                                c.verifiedAt(), c.approvedAt(), c.rejectedAt(), c.rejectionReason()))
                        .toList()),
                wallet == null ? null : wallet(wallet),
                wallet == null ? List.of() : transfers.stream().map(t -> transfer(t, wallet.walletId())).toList(),
                wallet == null ? List.of() : topups.stream().map(DataExportDocument::topup).toList());
    }

    private static Wallet wallet(WalletView view) {
        return new Wallet(view.walletId(), view.tier().name(), view.status().name(),
                view.balance().currency().getCurrencyCode(), view.balance().toDecimalString(),
                view.held().toDecimalString(), view.available().toDecimalString());
    }

    private static Transfer transfer(TransferRecord record, UUID ownWalletId) {
        boolean sent = record.senderWalletId().equals(ownWalletId);
        return new Transfer(record.transferId(), sent ? "SENT" : "RECEIVED",
                sent ? record.receiverWalletId() : record.senderWalletId(), record.amount().toDecimalString(),
                record.amount().currency().getCurrencyCode(), record.status(), record.note(), record.createdAt(),
                record.updatedAt());
    }

    private static Topup topup(TopupRecord record) {
        return new Topup(record.topupId(), record.amount().toDecimalString(),
                record.amount().currency().getCurrencyCode(), record.status(), record.gatewayOrderId(),
                record.gatewayPaymentId(), record.createdAt(), record.updatedAt());
    }

    /** Personal data in clear: never printed. */
    @Override
    public String toString() {
        return "DataExportDocument[format=" + format + ", requestId=" + requestId + ", consents=" + consents.size()
                + ", transfers=" + transfers.size() + ", topups=" + topups.size() + "]";
    }
}
