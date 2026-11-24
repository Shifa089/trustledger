package com.trustledger.aml.cases;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.aml.TestcontainersConfiguration;
import com.trustledger.aml.facts.application.TransactionFactRecorder;
import com.trustledger.aml.shared.security.JwtRoleConverter;
import com.trustledger.events.TransferCompletedV1;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/v1/aml/alerts} (FR-AML-2, SPEC §5) against real PostgreSQL, with alerts raised by the real rule
 * engine. Each test gets a fresh wallet with two alerts: three structured transfers (R2, HIGH), then one of a lakh
 * (R3, MEDIUM). Other tests share the database, so every query here filters by this wallet unless it is checking
 * that no filter at all also works.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AmlAlertControllerIT {

    private static final String ALERTS = "/api/v1/aml/alerts";
    private static final long STRUCTURED = 4_600_000;
    private static final long ONE_LAKH = 10_000_000;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TransactionFactRecorder recorder;

    @Autowired
    JdbcClient jdbc;

    private MockMvc mvc;
    private UUID wallet;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        wallet = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            transfer(receiver, now.minusSeconds(60L * (3 - i)), STRUCTURED);
        }
        transfer(receiver, now, ONE_LAKH);
        caseId = jdbc.sql("SELECT id FROM aml.cases WHERE wallet_id = :w").param("w", wallet)
                .query(UUID.class).single();
    }

    @Test
    void aWalletsAlertsComeNewestFirstWithTheirCase() throws Exception {
        mvc.perform(get(ALERTS).param("walletId", wallet.toString()).with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].ruleCode").value("R3"))
                .andExpect(jsonPath("$.content[0].severity").value("MEDIUM"))
                .andExpect(jsonPath("$.content[1].ruleCode").value("R2"))
                .andExpect(jsonPath("$.content[1].severity").value("HIGH"))
                .andExpect(jsonPath("$.content[0].walletId").value(wallet.toString()))
                .andExpect(jsonPath("$.content[1].walletId").value(wallet.toString()))
                .andExpect(jsonPath("$.content[0].caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.content[1].caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.content[0].details").isNotEmpty())
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    @Test
    void ruleAndSeverityFiltersNarrowTheListAndCombineWithTheWallet() throws Exception {
        mvc.perform(byWallet().param("ruleCode", "R2").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ruleCode").value("R2"));
        mvc.perform(byWallet().param("severity", "MEDIUM").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].ruleCode").value("R3"));
        mvc.perform(byWallet().param("ruleCode", "R2").param("severity", "MEDIUM").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
        // Well formed but used by no rule: nothing matches, not an error.
        mvc.perform(byWallet().param("ruleCode", "R9").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void ruleAndSeverityWithoutAWalletSearchEveryWallet() throws Exception {
        mvc.perform(get(ALERTS).param("ruleCode", "R3").param("severity", "MEDIUM").param("size", "100")
                        .with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].ruleCode").value(everyItem(
                        is("R3"))))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));
        mvc.perform(get(ALERTS).with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void pagesAreStableAndSplitTheList() throws Exception {
        mvc.perform(byWallet().param("size", "1").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].ruleCode").value("R3"));
        mvc.perform(byWallet().param("size", "1").param("page", "1").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ruleCode").value("R2"));
        mvc.perform(byWallet().param("size", "1").param("page", "2").with(officer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void badQueryValuesAre400() throws Exception {
        for (String[] bad : new String[][] {{"size", "101"}, {"size", "0"}, {"page", "-1"}, {"ruleCode", "X1"},
                {"ruleCode", "R123"}}) {
            mvc.perform(get(ALERTS).param(bad[0], bad[1]).with(officer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        mvc.perform(get(ALERTS).param("severity", "LOW").with(officer())).andExpect(status().isBadRequest());
        mvc.perform(get(ALERTS).param("walletId", "not-a-uuid").with(officer())).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder byWallet() {
        return get(ALERTS).param("walletId", wallet.toString());
    }

    private void transfer(UUID receiver, Instant at, long amountMinor) {
        recorder.record(new TransferCompletedV1(UUID.randomUUID(), at, wallet, receiver, UUID.randomUUID(),
                amountMinor, "INR"));
    }

    private static RequestPostProcessor officer() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of("COMPLIANCE_OFFICER"))))
                .authorities(new JwtRoleConverter());
    }
}
