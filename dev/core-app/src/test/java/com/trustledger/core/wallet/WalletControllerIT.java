package com.trustledger.core.wallet;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.ApiMockMvc.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET /wallets/me} and freeze/unfreeze over HTTP (SPEC §5, FR-W-2, FR-W-3). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class WalletControllerIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    WalletFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    private static RequestPostProcessor officer(String subject) {
        return token(subject, "officer@example.test", "Test Officer", "COMPLIANCE_OFFICER");
    }

    private ResultActions me(String subject) throws Exception {
        return mvc.perform(get("/api/v1/wallets/me").with(customer(subject)));
    }

    private ResultActions decide(RequestPostProcessor caller, UUID walletId, String action, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/compliance/wallets/{id}/" + action, walletId).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions freeze(String officerSubject, UUID walletId, String reason) throws Exception {
        return decide(officer(officerSubject), walletId, "freeze", "{\"reason\":\"" + reason + "\"}");
    }

    private ResultActions unfreeze(String officerSubject, UUID walletId, String reason) throws Exception {
        return decide(officer(officerSubject), walletId, "unfreeze", "{\"reason\":\"" + reason + "\"}");
    }

    // --- GET /wallets/me ---

    @Test
    void customerWithoutKycHasNoWallet() throws Exception {
        me(ApiMockMvc.newSubject())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("KYC_REQUIRED"));
    }

    @Test
    void minWalletShowsBalancesAndMinLimits() throws Exception {
        String subject = ApiMockMvc.newSubject();
        UUID walletId = fixtures.wallet(fixtures.user(subject), WalletTier.MIN);
        fixtures.fund(walletId, 150_050);
        fixtures.hold(walletId, 50_000);

        me(subject).andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId.toString()))
                .andExpect(jsonPath("$.tier").value("MIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balance").value("1500.50"))
                .andExpect(jsonPath("$.held").value("500.00"))
                .andExpect(jsonPath("$.available").value("1000.50"))
                .andExpect(jsonPath("$.limits.maxBalance").value("10000.00"))
                .andExpect(jsonPath("$.limits.monthlyLoad").value("10000.00"))
                .andExpect(jsonPath("$.limits.p2pAllowed").value(false))
                .andExpect(jsonPath("$.limits.dailyTransfer").value(nullValue()))
                .andExpect(jsonPath("$.limits.highValueThreshold").value(nullValue()))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void fullWalletShowsFullLimits() throws Exception {
        String subject = ApiMockMvc.newSubject();
        fixtures.wallet(fixtures.user(subject), WalletTier.FULL);

        me(subject).andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FULL"))
                .andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.limits.maxBalance").value("200000.00"))
                .andExpect(jsonPath("$.limits.p2pAllowed").value(true))
                .andExpect(jsonPath("$.limits.dailyTransfer").value("100000.00"))
                .andExpect(jsonPath("$.limits.highValueThreshold").value("50000.00"));
    }

    @Test
    void eachCustomerSeesOnlyTheirOwnWallet() throws Exception {
        String alice = ApiMockMvc.newSubject();
        String bob = ApiMockMvc.newSubject();
        UUID aliceWallet = fixtures.wallet(fixtures.user(alice), WalletTier.MIN);
        UUID bobWallet = fixtures.wallet(fixtures.user(bob), WalletTier.MIN);

        me(alice).andExpect(jsonPath("$.walletId").value(aliceWallet.toString()));
        me(bob).andExpect(jsonPath("$.walletId").value(bobWallet.toString()));
    }

    @Test
    void officerCannotUseTheCustomerEndpoint() throws Exception {
        mvc.perform(get("/api/v1/wallets/me").with(officer(ApiMockMvc.newSubject())))
                .andExpect(status().isForbidden());
    }

    // --- freeze / unfreeze ---

    @Test
    void freezeRecordsHistoryPublishesAndShowsToTheCustomer(ApplicationEvents events) throws Exception {
        String customerSubject = ApiMockMvc.newSubject();
        UUID userId = fixtures.user(customerSubject);
        UUID walletId = fixtures.wallet(userId, WalletTier.FULL);
        String officerSubject = ApiMockMvc.newSubject();
        UUID officerId = fixtures.user(officerSubject);

        freeze(officerSubject, walletId, "Bank reported fraud")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId.toString()))
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.currentFreeze.source").value("COMPLIANCE"))
                .andExpect(jsonPath("$.currentFreeze.reason").value("Bank reported fraud"))
                .andExpect(jsonPath("$.currentFreeze.frozenBy").value(officerId.toString()));

        Map<String, Object> row = jdbc.queryForMap(
                "select source, reason, frozen_by, unfrozen_at from wallet.wallet_freezes where wallet_id = ?",
                walletId);
        assertThat(row).containsEntry("source", "COMPLIANCE").containsEntry("reason", "Bank reported fraud")
                .containsEntry("frozen_by", officerId).containsEntry("unfrozen_at", null);
        assertThat(events.stream(WalletFrozen.class))
                .containsExactly(new WalletFrozen(walletId, userId, FreezeSource.COMPLIANCE));
        me(customerSubject).andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void freezingTwiceIsAConflict() throws Exception {
        UUID walletId = fixtures.wallet(WalletTier.FULL);
        String officerSubject = ApiMockMvc.newSubject();
        freeze(officerSubject, walletId, "first").andExpect(status().isOk());

        freeze(officerSubject, walletId, "second")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

        assertThat(jdbc.queryForObject("select count(*) from wallet.wallet_freezes where wallet_id = ?",
                Integer.class, walletId)).isEqualTo(1);
    }

    @Test
    void unfreezeClosesTheHistoryRowAndPublishes(ApplicationEvents events) throws Exception {
        String customerSubject = ApiMockMvc.newSubject();
        UUID userId = fixtures.user(customerSubject);
        UUID walletId = fixtures.wallet(userId, WalletTier.FULL);
        String maker = ApiMockMvc.newSubject();
        String other = ApiMockMvc.newSubject();
        UUID otherId = fixtures.user(other);
        freeze(maker, walletId, "check").andExpect(status().isOk());

        unfreeze(other, walletId, "Cleared after review")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentFreeze").value(nullValue()));

        Map<String, Object> row = jdbc.queryForMap(
                "select unfrozen_by, unfreeze_reason, unfrozen_at from wallet.wallet_freezes where wallet_id = ?",
                walletId);
        assertThat(row).containsEntry("unfrozen_by", otherId).containsEntry("unfreeze_reason", "Cleared after review");
        assertThat(row.get("unfrozen_at")).isNotNull();
        assertThat(events.stream(WalletUnfrozen.class)).containsExactly(new WalletUnfrozen(walletId, userId));
        me(customerSubject).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void unfreezingAnActiveWalletIsAConflict() throws Exception {
        unfreeze(ApiMockMvc.newSubject(), fixtures.wallet(WalletTier.MIN), "why")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void officerCannotUnfreezeTheirOwnWallet() throws Exception {
        String officerCustomer = ApiMockMvc.newSubject();
        UUID walletId = fixtures.wallet(fixtures.user(officerCustomer), WalletTier.FULL);
        freeze(ApiMockMvc.newSubject(), walletId, "check").andExpect(status().isOk());

        unfreeze(officerCustomer, walletId, "it is mine")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MAKER_CHECKER_VIOLATION"));

        assertThat(jdbc.queryForObject("select status from wallet.wallets where id = ?", String.class, walletId))
                .isEqualTo("FROZEN");
    }

    @Test
    void reasonIsRequired() throws Exception {
        UUID walletId = fixtures.wallet(WalletTier.MIN);
        RequestPostProcessor officer = officer(ApiMockMvc.newSubject());

        decide(officer, walletId, "freeze", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        decide(officer, walletId, "freeze", "{\"reason\":\"   \"}").andExpect(status().isBadRequest());
        decide(officer, walletId, "freeze", "{\"reason\":\"" + "r".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());
        decide(officer, walletId, "unfreeze", "{}").andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select status from wallet.wallets where id = ?", String.class, walletId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void unknownWalletIsNotFound() throws Exception {
        freeze(ApiMockMvc.newSubject(), UUID.randomUUID(), "x")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void customerCannotFreeze() throws Exception {
        UUID walletId = fixtures.wallet(WalletTier.MIN);

        decide(customer(ApiMockMvc.newSubject()), walletId, "freeze", "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden());
        decide(customer(ApiMockMvc.newSubject()), walletId, "unfreeze", "{\"reason\":\"x\"}")
                .andExpect(status().isForbidden());
    }
}
