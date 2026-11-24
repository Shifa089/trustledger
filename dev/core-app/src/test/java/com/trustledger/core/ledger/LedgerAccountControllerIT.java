package com.trustledger.core.ledger;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.shared.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/v1/admin/ledger/accounts/{code}/balance} (SPEC §5, FR-L-2) against real PostgreSQL: the answer is
 * the balance summed from the journal, for ADMIN and AUDITOR. Each test posts to fresh wallet accounts, so the
 * expected balances don't depend on other tests; the shared platform accounts are only checked for shape.
 * The role matrix (401/403) is {@code SecurityMatrixIT}'s.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerAccountControllerIT {

    private static final String BALANCE = "/api/v1/admin/ledger/accounts/{code}/balance";

    @Autowired
    WebApplicationContext context;

    @Autowired
    LedgerApi ledger;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    @Test
    void aWalletAccountShowsTheBalanceSummedFromItsJournalLines() throws Exception {
        String wallet = walletAccount();
        topup(wallet, 150_050);
        topup(wallet, 25);
        transfer(wallet, walletAccount(), 10_000);

        for (RequestPostProcessor caller : List.of(admin(), auditor())) {
            mvc.perform(balance(wallet).with(caller))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountCode").value(wallet))
                    .andExpect(jsonPath("$.normalSide").value("CREDIT"))
                    .andExpect(jsonPath("$.balance").value("1400.75"))
                    .andExpect(jsonPath("$.currency").value("INR"))
                    .andExpect(jsonPath("$.asOf", notNullValue()));
        }
    }

    @Test
    void aNewAccountIsZeroWithTwoDecimals() throws Exception {
        mvc.perform(balance(walletAccount()).with(auditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("0.00"));
    }

    @Test
    void aBalanceBelowZeroIsShownSigned() throws Exception {
        // A liability debited beyond its credits; the ledger itself allows it (suspense can go negative too).
        String wallet = walletAccount();
        ledger.post(PostingCommand.of(EntryType.P2P_TRANSFER, "TRANSFER", UUID.randomUUID(), List.of(
                PostingLine.debit(wallet, Money.of(1_250, Money.INR)),
                PostingLine.credit(AccountCodes.SUSPENSE, Money.of(1_250, Money.INR)))));

        mvc.perform(balance(wallet).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("-12.50"));
    }

    @Test
    void platformAccountsAreReadableWithTheirNormalSide() throws Exception {
        mvc.perform(balance(AccountCodes.GATEWAY_CLEARING).with(auditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value(AccountCodes.GATEWAY_CLEARING))
                .andExpect(jsonPath("$.normalSide").value("DEBIT"))
                .andExpect(jsonPath("$.balance").value(matchesPattern("-?\\d+\\.\\d{2}")));
        mvc.perform(balance(AccountCodes.SUSPENSE).with(auditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normalSide").value("CREDIT"));
    }

    @Test
    void anUnknownAccountIs404AndAMalformedCodeIs400() throws Exception {
        mvc.perform(balance("WALLET:" + UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        for (String bad : List.of("CASH", "platform:suspense", "PLATFORM:", "PLATFORM:SUS PENSE",
                "WALLET:" + "x".repeat(60))) {
            mvc.perform(balance(bad).with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
    }

    private static MockHttpServletRequestBuilder balance(String code) {
        return get(BALANCE, code);
    }

    private String walletAccount() {
        UUID walletId = UUID.randomUUID();
        ledger.openWalletAccount(walletId, Money.INR);
        return AccountCodes.wallet(walletId);
    }

    private void topup(String wallet, long minor) {
        ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                PostingLine.debit(AccountCodes.GATEWAY_CLEARING, Money.of(minor, Money.INR)),
                PostingLine.credit(wallet, Money.of(minor, Money.INR)))));
    }

    private void transfer(String from, String to, long minor) {
        ledger.post(PostingCommand.of(EntryType.P2P_TRANSFER, "TRANSFER", UUID.randomUUID(), List.of(
                PostingLine.debit(from, Money.of(minor, Money.INR)),
                PostingLine.credit(to, Money.of(minor, Money.INR)))));
    }

    private static RequestPostProcessor admin() {
        return ApiMockMvc.token(ApiMockMvc.newSubject(), "admin@example.test", "Test Admin", "ADMIN");
    }

    private static RequestPostProcessor auditor() {
        return ApiMockMvc.token(ApiMockMvc.newSubject(), "auditor@example.test", "Test Auditor", "AUDITOR");
    }
}
