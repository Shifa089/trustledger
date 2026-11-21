package com.trustledger.core.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTemplatesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant AT = Instant.parse("2026-11-21T08:35:00Z"); // 14:05 IST
    private static final UUID TRANSFER = UUID.fromString("0193a1b2-c3d4-7e5f-8a9b-0c1d2e3f3f2a");

    @Test
    void transferSentShowsAmountMaskedReceiverShortReferenceAndLocalTime() {
        EmailMessage mail = NotificationTemplates.transferSent(Money.parse("1500.50"), "R*** U***", TRANSFER, AT, IST);

        assertThat(mail.subject()).isEqualTo("You sent INR 1500.50");
        assertThat(mail.body())
                .contains("You sent INR 1500.50 to R*** U***.")
                .contains("Reference: ending 3f2a")
                .contains("Time: 21 Nov 2026, 14:05 IST")
                .doesNotContain(TRANSFER.toString());
    }

    @Test
    void transferReceivedNamesTheMaskedSender() {
        EmailMessage mail = NotificationTemplates.transferReceived(Money.parse("1.00"), "S*** E***", TRANSFER, AT, IST);

        assertThat(mail.subject()).isEqualTo("You received INR 1.00");
        assertThat(mail.body()).contains("You received INR 1.00 from S*** E***.").contains("ending 3f2a");
    }

    @Test
    void anUnknownCounterpartyIsDescribedNotLeftBlank() {
        assertThat(NotificationTemplates.transferSent(Money.parse("10.00"), null, TRANSFER, AT, IST).body())
                .contains("to " + NotificationTemplates.UNKNOWN_COUNTERPARTY + ".");
        assertThat(NotificationTemplates.transferReceived(Money.parse("10.00"), " ", TRANSFER, AT, IST).body())
                .contains("from " + NotificationTemplates.UNKNOWN_COUNTERPARTY + ".");
    }

    @Test
    void topupCapturedShowsAmountAndReference() {
        EmailMessage mail = NotificationTemplates.topupCaptured(Money.of(50_000, Money.INR), TRANSFER, AT, IST);

        assertThat(mail.subject()).isEqualTo("INR 500.00 added to your wallet");
        assertThat(mail.body()).contains("INR 500.00 was added").contains("ending 3f2a").contains("14:05 IST");
    }

    @Test
    void kycUpgradedExplainsEachLevel() {
        assertThat(NotificationTemplates.kycUpgraded(KycLevel.MIN).body())
                .contains("minimum KYC is approved").contains("Complete full KYC");
        assertThat(NotificationTemplates.kycUpgraded(KycLevel.FULL).body())
                .contains("full KYC is approved").contains("send money to other users");
        assertThat(NotificationTemplates.kycUpgraded(KycLevel.FULL).subject()).isEqualTo("Your KYC is approved");
    }

    @Test
    void walletFrozenNeverSaysWhyOrWhoNoTippingOff() {
        EmailMessage mail = NotificationTemplates.walletFrozen(TRANSFER, AT, IST);

        assertThat(mail.subject()).isEqualTo("Your wallet has been frozen");
        assertThat(mail.body()).contains("wallet ending 3f2a was frozen on 21 Nov 2026, 14:05 IST")
                .contains("contact TrustLedger support")
                .doesNotContainIgnoringCase("AML")
                .doesNotContainIgnoringCase("compliance")
                .doesNotContainIgnoringCase("suspicious")
                .doesNotContainIgnoringCase("reason");
    }

    @Test
    void everyMailIsPlainTextWithTheFooter() {
        for (EmailMessage mail : new EmailMessage[] {
                NotificationTemplates.transferSent(Money.parse("1.00"), "A***", TRANSFER, AT, IST),
                NotificationTemplates.transferReceived(Money.parse("1.00"), "A***", TRANSFER, AT, IST),
                NotificationTemplates.topupCaptured(Money.parse("1.00"), TRANSFER, AT, IST),
                NotificationTemplates.kycUpgraded(KycLevel.MIN),
                NotificationTemplates.walletFrozen(TRANSFER, AT, IST)}) {
            assertThat(mail.body()).startsWith("Hello,").contains("automated message").doesNotContain("<", ">");
        }
    }
}
