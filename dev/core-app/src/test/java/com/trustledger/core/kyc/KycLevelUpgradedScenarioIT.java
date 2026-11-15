package com.trustledger.core.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.application.FullKycService;
import com.trustledger.core.kyc.application.KycDocumentService;
import com.trustledger.core.kyc.application.KycReviewService;
import com.trustledger.core.kyc.application.MinKycCommand;
import com.trustledger.core.kyc.application.MinKycService;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.kyc.domain.DocumentType;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * FR-KYC-4: approval publishes {@link KycLevelUpgraded}. Spring Modulith {@link Scenario}: stimulate the
 * service, then wait for the event. Runs in the shared application context (same containers as all ITs).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnableScenarios
@RecordApplicationEvents
class KycLevelUpgradedScenarioIT {

    @Autowired
    MinKycService minKyc;

    @Autowired
    UserProvisioningService provisioning;

    @Autowired
    FullKycService fullKyc;

    @Autowired
    KycDocumentService documents;

    @Autowired
    KycReviewService review;

    @Test
    void minimumKycApprovalPublishesKycLevelUpgraded(Scenario scenario) {
        UUID userId = newUser();

        scenario.stimulate(() -> minKyc.submit(command(userId, LocalDate.of(1990, 1, 1))))
                .andWaitForEventOfType(KycLevelUpgraded.class)
                .matching(event -> event.userId().equals(userId))
                .toArriveAndVerify(event -> assertThat(event.level()).isEqualTo(KycLevel.MIN));
    }

    @Test
    void rejectedSubmissionPublishesNothing(ApplicationEvents events) {
        UUID userId = newUser();

        assertThatThrownBy(() -> minKyc.submit(command(userId, LocalDate.now().minusYears(10))))
                .isInstanceOf(TrustLedgerException.class);

        assertThat(events.stream(KycLevelUpgraded.class)).noneMatch(e -> e.userId().equals(userId));
    }

    @Test
    void fullApprovalPublishesKycLevelUpgradedFull(Scenario scenario) {
        UUID userId = newUser();
        UUID caseId = verifiedFullCase(userId);
        UUID checker = newUser();

        scenario.stimulate(() -> review.approve(caseId, checker))
                .andWaitForEventOfType(KycLevelUpgraded.class)
                .matching(event -> event.userId().equals(userId) && event.level() == KycLevel.FULL)
                .toArriveAndVerify(event -> assertThat(event.level()).isEqualTo(KycLevel.FULL));
    }

    @Test
    void verifyRejectAndARefusedApprovalPublishNoFullUpgrade(ApplicationEvents events) {
        UUID userId = newUser();
        UUID maker = newUser();
        UUID caseId = verifiedFullCase(userId, maker);

        assertThatThrownBy(() -> review.approve(caseId, maker)).isInstanceOf(TrustLedgerException.class);
        review.reject(caseId, newUser(), "Address proof unreadable");

        assertThat(events.stream(KycLevelUpgraded.class))
                .noneMatch(e -> e.userId().equals(userId) && e.level() == KycLevel.FULL);
    }

    private UUID verifiedFullCase(UUID userId) {
        return verifiedFullCase(userId, newUser());
    }

    /** MIN approved, FULL submitted with both required documents, verified by {@code maker}. */
    private UUID verifiedFullCase(UUID userId, UUID maker) {
        minKyc.submit(command(userId, LocalDate.of(1990, 1, 1)));
        UUID caseId = fullKyc.submit(userId, "12 Scenario Street, Pune").id();
        documents.upload(userId, caseId, DocumentType.PAN_CARD, KycFlows.PNG);
        documents.upload(userId, caseId, DocumentType.ADDRESS_PROOF, KycFlows.PDF);
        review.verify(caseId, maker);
        return caseId;
    }

    private UUID newUser() {
        return provisioning.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), null, "Scenario User"));
    }

    private static MinKycCommand command(UUID userId, LocalDate dob) {
        String phone = "8" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        return new MinKycCommand(userId, "Scenario User", dob, phone, "ABCPE1234F");
    }
}
