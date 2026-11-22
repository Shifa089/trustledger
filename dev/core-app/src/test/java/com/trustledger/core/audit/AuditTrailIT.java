package com.trustledger.core.audit;

import static com.trustledger.core.ApiMockMvc.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import com.trustledger.core.wallet.AmlFreezeOutcome;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletUnfrozen;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-A-1 end to end: the domain events of real flows (and, for events whose flows need a payment gateway or two
 * funded wallets, events published in a transaction) produce one audit row each, with actor, role, IP, correlation
 * ID and masked before/after, in the publishing transaction, and the chain stays intact.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditTrailIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserProvisioningService users;

    @Autowired
    WalletProvisioningService provisioning;

    @Autowired
    WalletApi wallets;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    MockMvc mvc;
    AuditRows rows;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        rows = new AuditRows(jdbc, json);
    }

    private UUID user(String subject) {
        return users.ensureUser(new TokenIdentity(subject, null, "Audit Test User"));
    }

    private UUID wallet(UUID userId) {
        provisioning.apply(userId, KycLevel.FULL);
        return wallets.findByUser(userId).orElseThrow().walletId();
    }

    private ResultActions decide(String officerSubject, UUID walletId, String action, String correlationId,
            String ip) throws Exception {
        return mvc.perform(post("/api/v1/compliance/wallets/{id}/" + action, walletId)
                .with(token(officerSubject, "officer@example.test", "Test Officer", "COMPLIANCE_OFFICER"))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header(CorrelationIdFilter.HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer ABCDE1234F reported fraud\"}"));
    }

    @Test
    void anOfficersFreezeAndUnfreezeAreAuditedWithWhoWhereAndWhat() throws Exception {
        UUID walletId = wallet(user(ApiMockMvc.newSubject()));
        String maker = ApiMockMvc.newSubject();
        UUID makerId = user(maker);
        String checker = ApiMockMvc.newSubject();
        UUID checkerId = user(checker);

        decide(maker, walletId, "freeze", "audit-freeze-1", "203.0.113.7").andExpect(status().isOk());
        decide(checker, walletId, "unfreeze", "audit-unfreeze-1", "2001:db8::42").andExpect(status().isOk());

        AuditRows.Row frozen = rows.single(walletId, "WALLET_FROZEN");
        assertThat(frozen.event().actorId()).isEqualTo(makerId);
        assertThat(frozen.event().actorRole()).isEqualTo("COMPLIANCE_OFFICER");
        assertThat(frozen.event().entityType()).isEqualTo("WALLET");
        assertThat(frozen.event().ip()).isEqualTo("203.0.113.7");
        assertThat(frozen.event().correlationId()).isEqualTo("audit-freeze-1");
        assertThat(frozen.event().before()).isEqualTo(Map.of("status", "ACTIVE"));
        assertThat(frozen.event().after()).isEqualTo(Map.of("status", "FROZEN", "freezeSource", "COMPLIANCE"));

        AuditRows.Row unfrozen = rows.single(walletId, "WALLET_UNFROZEN");
        assertThat(unfrozen.event().actorId()).isEqualTo(checkerId);
        assertThat(unfrozen.event().ip()).isEqualTo("2001:db8::42");
        assertThat(unfrozen.event().correlationId()).isEqualTo("audit-unfreeze-1");
        assertThat(unfrozen.event().before()).isEqualTo(Map.of("status", "FROZEN"));
        assertThat(unfrozen.event().after()).isEqualTo(Map.of("status", "ACTIVE"));
        assertThat(unfrozen.seq()).isGreaterThan(frozen.seq());

        // The officer's free-text reason (which here quotes a PAN) never reaches the audit trail.
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where entity_id = ? and "
                + "(before::text like '%ABCDE1234F%' or after::text like '%ABCDE1234F%')", Integer.class, walletId))
                .isZero();
        rows.assertChainIntact();
    }

    @Test
    void walletCreationIsAuditedAsTheSystem() {
        UUID userId = user(ApiMockMvc.newSubject());
        UUID walletId = wallet(userId);

        AuditRows.Row created = rows.single(walletId, "WALLET_CREATED");
        assertThat(created.event().actorId()).isNull();
        assertThat(created.event().actorRole()).isEqualTo("SYSTEM");
        // No IP assertion: Spring's test listener binds a mock request (127.0.0.1) to the test thread.
        assertThat(created.event().after()).isEqualTo(Map.of("userId", userId.toString(), "status", "ACTIVE"));
    }

    /** FR-AML-3: the AML service's freeze arrives without a user token, so the system is the actor. */
    @Test
    void anAmlFreezeIsAuditedAsTheSystem() {
        UUID walletId = wallet(user(ApiMockMvc.newSubject()));

        assertThat(wallets.freezeForAml(UUID.randomUUID(), walletId, UUID.randomUUID(), "R2"))
                .isEqualTo(AmlFreezeOutcome.FROZEN);

        AuditRows.Row frozen = rows.single(walletId, "WALLET_FROZEN");
        assertThat(frozen.event().actorId()).isNull();
        assertThat(frozen.event().actorRole()).isEqualTo("SYSTEM");
        assertThat(frozen.event().after()).isEqualTo(Map.of("status", "FROZEN", "freezeSource", "AML"));
    }

    @Test
    void kycLevelUpgradeIsAudited() {
        UUID userId = user(ApiMockMvc.newSubject());

        tx.executeWithoutResult(status -> events.publishEvent(new KycLevelUpgraded(userId, KycLevel.MIN)));

        AuditRows.Row upgraded = rows.single(userId, "KYC_LEVEL_UPGRADED");
        assertThat(upgraded.event().entityType()).isEqualTo("USER");
        assertThat(upgraded.event().after()).isEqualTo(Map.of("kycLevel", "MIN"));
    }

    @Test
    void capturedTopupIsAuditedWithItsCorrelationId() {
        UUID topupId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        tx.executeWithoutResult(status -> events.publishEvent(new TopupCaptured(topupId, walletId,
                Money.of(150_050, Money.INR), entryId, Instant.parse("2026-11-22T09:00:00Z"),
                new EventMetadata(UUID.randomUUID(), "webhook-corr-1"))));

        AuditRows.Row captured = rows.single(topupId, "TOPUP_CAPTURED");
        assertThat(captured.event().entityType()).isEqualTo("TOPUP");
        assertThat(captured.event().correlationId()).isEqualTo("webhook-corr-1");
        assertThat(captured.event().after()).isEqualTo(Map.of("status", "CAPTURED", "walletId", walletId.toString(),
                "amount", "1500.50", "currency", "INR", "journalEntryId", entryId.toString()));
    }

    @Test
    void completedTransferIsAudited() {
        UUID transferId = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();

        tx.executeWithoutResult(status -> events.publishEvent(new TransferCompleted(transferId, sender, receiver,
                Money.of(25_000, Money.INR), entryId, Instant.parse("2026-11-22T09:00:00Z"),
                new EventMetadata(UUID.randomUUID(), "transfer-corr-1"))));

        AuditRows.Row completed = rows.single(transferId, "TRANSFER_COMPLETED");
        assertThat(completed.event().correlationId()).isEqualTo("transfer-corr-1");
        assertThat(completed.event().after()).containsEntry("amount", "250.00")
                .containsEntry("senderWalletId", sender.toString())
                .containsEntry("receiverWalletId", receiver.toString());
    }

    /** Atomic with the business change: a rolled-back transaction leaves no audit row behind. */
    @Test
    void aRolledBackChangeLeavesNoAuditRow() {
        UUID walletId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            events.publishEvent(new WalletUnfrozen(walletId, UUID.randomUUID()));
            status.setRollbackOnly();
        });

        assertThat(rows.forEntity(walletId)).isEmpty();
    }

    /**
     * A refused business change (second freeze of a frozen wallet) is rolled back with no domain-fact row; the
     * officer's refused request is recorded as such by the {@code @Audited} aspect (Day 20).
     */
    @Test
    void aRefusedFreezeLeavesNoFactRowOnlyTheRefusedRequest() throws Exception {
        UUID walletId = wallet(user(ApiMockMvc.newSubject()));
        String officer = ApiMockMvc.newSubject();
        user(officer);

        decide(officer, walletId, "freeze", "audit-freeze-2", "203.0.113.8").andExpect(status().isOk());
        decide(officer, walletId, "freeze", "audit-freeze-3", "203.0.113.8").andExpect(status().is4xxClientError());

        assertThat(rows.single(walletId, "WALLET_FROZEN").event().correlationId()).isEqualTo("audit-freeze-2");
        assertThat(rows.forEntity(walletId).stream().filter(r -> r.event().action().equals("WALLET_FREEZE"))
                .map(r -> r.event().after().get("outcome")))
                .containsExactly("SUCCEEDED", "REJECTED");
    }
}
