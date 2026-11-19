package com.trustledger.core.payments;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /transfers} and {@code GET /transfers/{id}} through the production filter chain (security, JIT
 * provisioning, rate limits, idempotency body caching): the required key, replay, key reuse with another body,
 * request validation, roles, and BOLA on reads.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferControllerIT {

    private static final String URL = "/api/v1/transfers";

    @Autowired
    WebApplicationContext context;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    JsonMapper json;

    MockMvc mvc;
    Customer sender;
    Customer receiver;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        sender = fixtures.customer(WalletTier.FULL, 100_000); // ₹1,000
        receiver = fixtures.customer(WalletTier.FULL, 0);
    }

    private String body(String amount) {
        return "{\"recipientWalletId\":\"" + receiver.walletId() + "\",\"amount\":\"" + amount
                + "\",\"note\":\"Dinner\"}";
    }

    private MockHttpServletRequestBuilder transfer(String key, String body) {
        MockHttpServletRequestBuilder builder = post(URL).with(customer(sender.subject()))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? builder : builder.header("Idempotency-Key", key);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private UUID createTransfer() throws Exception {
        MockHttpServletResponse response = mvc.perform(transfer(newKey(), body("25.00")))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asString());
    }

    // ---- POST ----------------------------------------------------------------------------------------------

    @Test
    void createsACompletedTransfer() throws Exception {
        mvc.perform(transfer(newKey(), body("250.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.senderWalletId").value(sender.walletId().toString()))
                .andExpect(jsonPath("$.receiverWalletId").value(receiver.walletId().toString()))
                .andExpect(jsonPath("$.amount").value("250.00"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.note").value("Dinner"))
                .andExpect(jsonPath("$.id").exists());

        assertThat(fixtures.wallet(sender.walletId()).balance()).isEqualTo(Money.of(75_000, Money.INR));
    }

    @Test
    void sendsByMobileNumber() throws Exception {
        String body = "{\"recipientMobile\":\"" + receiver.phone() + "\",\"amount\":\"1.00\"}";

        mvc.perform(transfer(newKey(), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiverWalletId").value(receiver.walletId().toString()));
    }

    @Test
    void missingIdempotencyKeyIs400AndMovesNothing() throws Exception {
        mvc.perform(transfer(null, body("10.00"))).andExpect(status().isBadRequest());

        assertThat(fixtures.transfersFrom(sender.walletId())).isZero();
        assertThat(fixtures.wallet(sender.walletId()).balance()).isEqualTo(Money.of(100_000, Money.INR));
    }

    /** M6 "Done when" 1: same key twice -> one transfer, same response. */
    @Test
    void replayWithTheSameKeyReturnsTheSameResponseAndMovesMoneyOnce() throws Exception {
        String key = newKey();
        MockHttpServletResponse first = mvc.perform(transfer(key, body("100.00")))
                .andExpect(status().isCreated()).andReturn().getResponse();

        MockHttpServletResponse second = mvc.perform(transfer(key, body("100.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse();

        JsonNode original = json.readTree(first.getContentAsString());
        assertThat(json.readTree(second.getContentAsString())).isEqualTo(original);
        assertThat(fixtures.transfersFrom(sender.walletId())).isOne();
        assertThat(fixtures.wallet(sender.walletId()).balance()).isEqualTo(Money.of(90_000, Money.INR));
        assertThat(fixtures.ledgerBalance(sender.walletId())).isEqualTo(90_000);
    }

    /** M6 "Done when" 1: same key with a different body -> 422. */
    @Test
    void sameKeyWithAnotherBodyIs422() throws Exception {
        String key = newKey();
        mvc.perform(transfer(key, body("100.00"))).andExpect(status().isCreated());

        mvc.perform(transfer(key, body("200.00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(fixtures.transfersFrom(sender.walletId())).isOne();
    }

    @Test
    void refusalReleasesTheKeySoAnHonestRetryCanSucceed() throws Exception {
        String key = newKey();
        mvc.perform(transfer(key, body("2000.00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"));

        fixtures.fund(sender.walletId(), 200_000);
        mvc.perform(transfer(key, body("2000.00"))).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.001", "-1.00", "abc", "0", "1e3", ""})
    void invalidAmountIs400(String amount) throws Exception {
        mvc.perform(transfer(newKey(), body(amount))).andExpect(status().isBadRequest());

        assertThat(fixtures.transfersFrom(sender.walletId())).isZero();
    }

    @Test
    void bothOrNeitherRecipientIs400() throws Exception {
        String both = "{\"recipientMobile\":\"" + receiver.phone() + "\",\"recipientWalletId\":\""
                + receiver.walletId() + "\",\"amount\":\"1.00\"}";
        mvc.perform(transfer(newKey(), both)).andExpect(status().isBadRequest());
        mvc.perform(transfer(newKey(), "{\"amount\":\"1.00\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void tooLongNoteIs400() throws Exception {
        String body = "{\"recipientWalletId\":\"" + receiver.walletId() + "\",\"amount\":\"1.00\",\"note\":\""
                + "x".repeat(141) + "\"}";
        mvc.perform(transfer(newKey(), body)).andExpect(status().isBadRequest());
    }

    @Test
    void complianceOfficerCannotSend() throws Exception {
        mvc.perform(post(URL).with(ApiMockMvc.token(ApiMockMvc.newSubject(), null, "Officer", "COMPLIANCE_OFFICER"))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body("1.00")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(post(URL).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body("1.00")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    // ---- GET (BOLA) ----------------------------------------------------------------------------------------

    @Test
    void senderAndReceiverCanReadTheTransfer() throws Exception {
        UUID id = createTransfer();

        mvc.perform(get(URL + "/" + id).with(customer(sender.subject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.amount").value("25.00"));
        mvc.perform(get(URL + "/" + id).with(customer(receiver.subject())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    /** BOLA: another customer gets the same 404 as for an ID that doesn't exist. */
    @Test
    void anotherCustomerCannotReadTheTransfer() throws Exception {
        UUID id = createTransfer();
        Customer stranger = fixtures.customer(WalletTier.FULL, 0);

        MockHttpServletResponse foreign = mvc.perform(get(URL + "/" + id).with(customer(stranger.subject())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andReturn().getResponse();
        MockHttpServletResponse unknown = mvc.perform(get(URL + "/" + UUID.randomUUID())
                        .with(customer(stranger.subject())))
                .andExpect(status().isNotFound())
                .andReturn().getResponse();

        assertThat(json.readTree(foreign.getContentAsString()).get("detail"))
                .isEqualTo(json.readTree(unknown.getContentAsString()).get("detail"));
    }

    @Test
    void customerWithoutAWalletCannotReadTransfers() throws Exception {
        UUID id = createTransfer();
        Customer noWallet = fixtures.userWithoutWallet();

        mvc.perform(get(URL + "/" + id).with(customer(noWallet.subject()))).andExpect(status().isNotFound());
    }

    @Test
    void complianceOfficerCannotUseTheCustomerReadEndpoint() throws Exception {
        UUID id = createTransfer();

        mvc.perform(get(URL + "/" + id)
                        .with(ApiMockMvc.token(ApiMockMvc.newSubject(), null, "Officer", "COMPLIANCE_OFFICER")))
                .andExpect(status().isForbidden());
    }
}
