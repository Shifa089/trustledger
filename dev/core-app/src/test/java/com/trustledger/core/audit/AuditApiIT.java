package com.trustledger.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.application.WalletProvisioningService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /audit/events} and {@code GET /audit/verify} (SPEC §5, FR-A-2) against the real trail.
 * <p>
 * The tamper tests edit rows directly in SQL, as a DBA could: the append-only triggers are bypassed with
 * {@code session_replication_role = replica} (superuser in the test container), and the original row is always put
 * back in {@code finally}, because every IT shares this database and checks the whole chain.
 * Done when (M10): "editing a row directly in SQL makes /audit/verify report the exact broken sequence number".
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditApiIT {

    private static final String EVENTS = "/api/v1/audit/events";
    private static final String VERIFY = "/api/v1/audit/verify";

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

    @Autowired
    TransactionTemplate tx;

    MockMvc mvc;
    AuditRows rows;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        rows = new AuditRows(jdbc, json);
    }

    private static MockHttpServletRequestBuilder asAuditor(MockHttpServletRequestBuilder request) {
        return request.with(ApiMockMvc.token(ApiMockMvc.newSubject(), "auditor@example.test", "Test Auditor",
                "AUDITOR"));
    }

    /** A wallet with three events: WALLET_CREATED, then a freeze's WALLET_FROZEN and WALLET_FREEZE. */
    private UUID frozenWallet(String officer) throws Exception {
        UUID userId = users.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), null, "Audit Api User"));
        provisioning.apply(userId, KycLevel.FULL);
        UUID walletId = wallets.findByUser(userId).orElseThrow().walletId();
        mvc.perform(post("/api/v1/compliance/wallets/{id}/freeze", walletId)
                        .with(ApiMockMvc.token(officer, "officer@example.test", "Test Officer", "COMPLIANCE_OFFICER"))
                        .header(HttpHeaders.USER_AGENT, "Audit-Api-IT/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Audit API test\"}"))
                .andExpect(status().isOk());
        return walletId;
    }

    private ResultActions verify() throws Exception {
        return mvc.perform(asAuditor(get(VERIFY)));
    }

    /** Runs SQL on one connection with the append-only triggers switched off, then switches them back on. */
    private void asDba(String sql, Object... args) {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update(sql, args);
        });
    }

    // ---- GET /audit/events ----

    @Test
    void anEntitysEventsComeNewestFirstWithHashesAndMaskedState() throws Exception {
        String officer = ApiMockMvc.newSubject();
        UUID walletId = frozenWallet(officer);
        UUID officerId = identity.findUserId(officer).orElseThrow();

        mvc.perform(asAuditor(get(EVENTS).param("entityType", "WALLET").param("entityId", walletId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].action").value(org.hamcrest.Matchers.contains(
                        "WALLET_FREEZE", "WALLET_FROZEN", "WALLET_CREATED")))
                .andExpect(jsonPath("$.items[0].actorId").value(officerId.toString()))
                .andExpect(jsonPath("$.items[0].actorRole").value("COMPLIANCE_OFFICER"))
                .andExpect(jsonPath("$.items[0].userAgent").value("Audit-Api-IT/1.0"))
                .andExpect(jsonPath("$.items[0].before.status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].after.status").value("FROZEN"))
                .andExpect(jsonPath("$.items[0].after.reasonLength").value(14))
                .andExpect(jsonPath("$.items[0].after.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].hash").value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.items[2].actorRole").value("SYSTEM"));
    }

    @Test
    void filtersCombineAndPagesAreStable() throws Exception {
        String officer = ApiMockMvc.newSubject();
        UUID walletId = frozenWallet(officer);
        frozenWallet(officer);
        UUID officerId = identity.findUserId(officer).orElseThrow();

        // The officer acted on two wallets: two requests and two facts.
        mvc.perform(asAuditor(get(EVENTS).param("actorId", officerId.toString())))
                .andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(asAuditor(get(EVENTS).param("actorId", officerId.toString()).param("action", "WALLET_FREEZE")
                        .param("size", "1").param("page", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].entityId").value(walletId.toString()));
        mvc.perform(asAuditor(get(EVENTS).param("entityId", walletId.toString())
                        .param("from", "2099-01-01T00:00:00Z")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(asAuditor(get(EVENTS).param("entityId", walletId.toString())
                        .param("from", "2020-01-01T00:00:00Z").param("to", "2099-01-01T00:00:00Z")))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void badFiltersAre400() throws Exception {
        mvc.perform(asAuditor(get(EVENTS).param("entityType", "wallet")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(asAuditor(get(EVENTS).param("from", "2026-11-22T00:00:00Z").param("to", "2026-11-21T00:00:00Z")))
                .andExpect(status().isBadRequest());
        mvc.perform(asAuditor(get(EVENTS).param("size", "101"))).andExpect(status().isBadRequest());
        mvc.perform(asAuditor(get(EVENTS).param("entityId", "not-a-uuid"))).andExpect(status().isBadRequest());
        mvc.perform(asAuditor(get(EVENTS).param("from", "yesterday"))).andExpect(status().isBadRequest());
    }

    // ---- GET /audit/verify ----

    @Test
    void anIntactChainVerifiesUpToTheLatestEvent() throws Exception {
        frozenWallet(ApiMockMvc.newSubject());
        AuditRows.Row last = rows.all().getLast();

        verify().andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.lastSeq").value(last.seq()))
                .andExpect(jsonPath("$.lastHash").value(last.hash()))
                .andExpect(jsonPath("$.checkedEvents").value(rows.all().size()))
                .andExpect(jsonPath("$.brokenSeq").value(nullValue()));
    }

    @Test
    void editingARowInSqlIsReportedAtThatExactSeq() throws Exception {
        UUID walletId = frozenWallet(ApiMockMvc.newSubject());
        AuditRows.Row target = rows.single(walletId, "WALLET_FROZEN");
        String original = jdbc.queryForObject("select after::text from audit.audit_events where seq = ?",
                String.class, target.seq());

        // A "harmless" edit: the freeze never happened.
        asDba("update audit.audit_events set after = jsonb_set(after, '{status}', '\"ACTIVE\"') where seq = ?",
                target.seq());
        try {
            verify().andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("BROKEN"))
                    .andExpect(jsonPath("$.brokenSeq").value(target.seq()))
                    .andExpect(jsonPath("$.reason").value("HASH_MISMATCH"));
        } finally {
            asDba("update audit.audit_events set after = cast(? as jsonb) where seq = ?", original, target.seq());
        }
        verify().andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void deletingARowInSqlIsReportedAtTheRowAfterIt() throws Exception {
        UUID walletId = frozenWallet(ApiMockMvc.newSubject());
        long deleted = rows.single(walletId, "WALLET_FROZEN").seq();
        long next = jdbc.queryForObject("select min(seq) from audit.audit_events where seq > ?", Long.class, deleted);

        jdbc.execute("drop table if exists public.audit_api_it_saved");
        jdbc.update("create table public.audit_api_it_saved as select * from audit.audit_events where seq = ?",
                deleted);
        asDba("delete from audit.audit_events where seq = ?", deleted);
        try {
            verify().andExpect(jsonPath("$.status").value("BROKEN"))
                    .andExpect(jsonPath("$.brokenSeq").value(next))
                    .andExpect(jsonPath("$.reason").value("PREV_HASH_MISMATCH"));
        } finally {
            asDba("insert into audit.audit_events select * from public.audit_api_it_saved");
            jdbc.execute("drop table public.audit_api_it_saved");
        }
        verify().andExpect(jsonPath("$.status").value("OK"));
    }
}
