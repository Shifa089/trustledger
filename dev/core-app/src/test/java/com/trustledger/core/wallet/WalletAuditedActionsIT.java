package com.trustledger.core.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.audit.AuditRows;
import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-A-1 (Day 20): an officer's freeze and unfreeze, and an admin's reconciliation run, are audited by the
 * {@code @Audited} aspect with who, from where and the masked state change. A freeze has two events: the request
 * (WALLET_FREEZE, written after the commit) and the domain fact (WALLET_FROZEN, written in the business
 * transaction), joined by the correlation ID.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WalletAuditedActionsIT {

    private static final String REASON = "Customer ABCDE1234F, phone 9876543210, reported fraud";

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserProvisioningService users;

    @Autowired
    WalletProvisioningService provisioning;

    @Autowired
    WalletApi wallets;

    @Autowired
    IdentityApi identity;

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

    private UUID fullWallet() {
        UUID userId = users.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), null, "Wallet Audit User"));
        provisioning.apply(userId, KycLevel.FULL);
        return wallets.findByUser(userId).orElseThrow().walletId();
    }

    private ResultActions decide(String officer, UUID walletId, String action, String correlationId)
            throws Exception {
        return mvc.perform(post("/api/v1/compliance/wallets/{id}/" + action, walletId)
                .with(ApiMockMvc.token(officer, "officer@example.test", "Test Officer", "COMPLIANCE_OFFICER"))
                .with(r -> {
                    r.setRemoteAddr("192.0.2.30");
                    return r;
                })
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Officer)")
                .header(CorrelationIdFilter.HEADER, correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + REASON + "\"}"));
    }

    @Test
    void freezeAndUnfreezeAreAuditedAsRequestsBesideTheirDomainFacts() throws Exception {
        UUID walletId = fullWallet();
        String officer = ApiMockMvc.newSubject();

        decide(officer, walletId, "freeze", "wallet-freeze-1").andExpect(status().isOk());
        decide(officer, walletId, "unfreeze", "wallet-unfreeze-1").andExpect(status().isOk());

        UUID officerId = identity.findUserId(officer).orElseThrow();
        AuditRows.Row freezeRow = rows.single(walletId, "WALLET_FREEZE");
        AuditEvent freeze = freezeRow.event();
        assertThat(freeze.actorId()).isEqualTo(officerId);
        assertThat(freeze.actorRole()).isEqualTo("COMPLIANCE_OFFICER");
        assertThat(freeze.ip()).isEqualTo("192.0.2.30");
        // CR/LF in a header is refused earlier by Spring Security's StrictHttpFirewall; AuditEvent strips any other
        // control character before hashing (AuditEventTest).
        assertThat(freeze.userAgent()).isEqualTo("Mozilla/5.0 (Officer)");
        assertThat(freeze.correlationId()).isEqualTo("wallet-freeze-1");
        assertThat(freeze.before()).isEqualTo(Map.of("status", "ACTIVE", "tier", "FULL"));
        assertThat(freeze.after()).isEqualTo(Map.of("status", "FROZEN", "tier", "FULL",
                "reasonLength", REASON.length(), "outcome", "SUCCEEDED"));

        AuditRows.Row frozen = rows.single(walletId, "WALLET_FROZEN");
        assertThat(frozen.event().correlationId()).isEqualTo("wallet-freeze-1");
        assertThat(frozen.event().userAgent()).isEqualTo(freeze.userAgent());
        assertThat(frozen.seq()).isLessThan(freezeRow.seq());

        AuditEvent unfreeze = rows.single(walletId, "WALLET_UNFREEZE").event();
        assertThat(unfreeze.before()).containsEntry("status", "FROZEN");
        assertThat(unfreeze.after()).containsEntry("status", "ACTIVE").containsEntry("outcome", "SUCCEEDED");

        // The reason quotes a PAN and a phone number: neither reaches the trail.
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where entity_id = ? and "
                + "(before::text ~ '(ABCDE1234F|9876543210|fraud)' or after::text ~ '(ABCDE1234F|9876543210|fraud)')",
                Integer.class, walletId)).isZero();
        rows.assertChainIntact();
    }

    @Test
    void freezingAnUnknownWalletIsRecordedAsRejected() throws Exception {
        UUID unknown = UUID.randomUUID();

        decide(ApiMockMvc.newSubject(), unknown, "freeze", "wallet-freeze-404").andExpect(status().isNotFound());

        AuditEvent refused = rows.single(unknown, "WALLET_FREEZE").event();
        assertThat(refused.before()).isNull();
        assertThat(refused.after()).isEqualTo(Map.of("reasonLength", REASON.length(), "outcome", "REJECTED",
                "errorCode", "NOT_FOUND"));
    }

    @Test
    void aManualReconciliationRunIsAuditedWithItsCounts() throws Exception {
        String admin = ApiMockMvc.newSubject();

        mvc.perform(post("/api/v1/admin/reconciliation/run")
                        .with(ApiMockMvc.token(admin, "admin@example.test", "Test Admin", "ADMIN"))
                        .header(CorrelationIdFilter.HEADER, "recon-run-1"))
                .andExpect(status().isOk());

        UUID adminId = identity.findUserId(admin).orElseThrow();
        List<Map<String, Object>> runs = jdbc.queryForList("select entity_id, actor_role, entity_type from "
                + "audit.audit_events where action = 'RECONCILIATION_RUN' and actor_id = ?", adminId);
        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.get("actor_role")).isEqualTo("ADMIN");
            assertThat(run.get("entity_type")).isEqualTo("RECONCILIATION_RUN");
        });
        AuditEvent run = rows.single((UUID) runs.getFirst().get("entity_id"), "RECONCILIATION_RUN").event();
        assertThat(run.correlationId()).isEqualTo("recon-run-1");
        assertThat(run.before()).isNull();
        assertThat(run.after()).containsKeys("walletsChecked", "mismatches", "unlinked", "truncated")
                .containsEntry("outcome", "SUCCEEDED")
                .doesNotContainKey("listed");
    }
}
