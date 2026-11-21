package com.trustledger.core.notification.domain;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Plain-text e-mail templates for FR-N-1. Pure functions, so every wording is unit-tested.
 * <p>
 * <b>Masked details only.</b> An e-mail travels and is stored outside our control, so it shows the amount, the
 * counterparty's masked name ({@code T*** C***}), the last 4 characters of IDs and the time, and never an e-mail
 * address, phone number, full name or full ID.
 * <p>
 * <b>No tipping off.</b> The frozen-wallet mail never says <em>why</em> or <em>who</em> froze the wallet: telling a
 * customer that AML froze it would warn a suspect (PMLA forbids tipping off).
 */
public final class NotificationTemplates {

    /** Used when the counterparty's name is unknown (no name in the token, or an erased user). */
    static final String UNKNOWN_COUNTERPARTY = "another TrustLedger user";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH);

    private static final String FOOTER = """

            If you did not expect this, contact TrustLedger support.

            TrustLedger
            This is an automated message; replies are not read.
            """;

    private NotificationTemplates() {
    }

    public static EmailMessage transferSent(Money amount, String receiverMaskedName, UUID transferId, Instant at,
            ZoneId zone) {
        return new EmailMessage("You sent " + money(amount), """
                Hello,

                You sent %s to %s.
                Reference: %s
                Time: %s
                """.formatted(money(amount), counterparty(receiverMaskedName), reference(transferId), when(at, zone))
                + FOOTER);
    }

    public static EmailMessage transferReceived(Money amount, String senderMaskedName, UUID transferId, Instant at,
            ZoneId zone) {
        return new EmailMessage("You received " + money(amount), """
                Hello,

                You received %s from %s.
                Reference: %s
                Time: %s
                """.formatted(money(amount), counterparty(senderMaskedName), reference(transferId), when(at, zone))
                + FOOTER);
    }

    public static EmailMessage topupCaptured(Money amount, UUID topupId, Instant at, ZoneId zone) {
        return new EmailMessage(money(amount) + " added to your wallet", """
                Hello,

                %s was added to your TrustLedger wallet.
                Reference: %s
                Time: %s
                """.formatted(money(amount), reference(topupId), when(at, zone)) + FOOTER);
    }

    public static EmailMessage kycUpgraded(KycLevel level) {
        Objects.requireNonNull(level, "level");
        String what = switch (level) {
            case MIN -> """
                    Your minimum KYC is approved and your wallet is open.
                    You can add money within the minimum-KYC limits. Complete full KYC to send money to others.""";
            case FULL -> """
                    Your full KYC is approved.
                    Your wallet now has the full-KYC limits and can send money to other users.""";
        };
        return new EmailMessage("Your KYC is approved", "Hello,\n\n" + what + "\n" + FOOTER);
    }

    public static EmailMessage walletFrozen(UUID walletId, Instant at, ZoneId zone) {
        return new EmailMessage("Your wallet has been frozen", """
                Hello,

                Your TrustLedger wallet %s was frozen on %s.
                While it is frozen you cannot send or add money.
                Please contact TrustLedger support for help.
                """.formatted(reference(walletId), when(at, zone)) + FOOTER);
    }

    /** {@code INR 1500.50}: always two decimals, never a float. */
    static String money(Money amount) {
        Objects.requireNonNull(amount, "amount");
        return amount.currency().getCurrencyCode() + " " + amount.toDecimalString();
    }

    /** {@code ending 3f2a}: enough to match a statement line, not enough to look anything up. */
    static String reference(UUID id) {
        String text = Objects.requireNonNull(id, "id").toString();
        return "ending " + text.substring(text.length() - 4);
    }

    private static String counterparty(String maskedName) {
        return maskedName == null || maskedName.isBlank() ? UNKNOWN_COUNTERPARTY : maskedName;
    }

    private static String when(Instant at, ZoneId zone) {
        return WHEN.format(Objects.requireNonNull(at, "at").atZone(Objects.requireNonNull(zone, "zone")));
    }
}
