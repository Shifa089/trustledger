package com.trustledger.core.payments;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /compliance/transfers}, {@code POST /compliance/transfers/{id}/approve} and {@code /reject} through
 * the production filter chain: the queue (oldest first, no note), the decision with the officer from the token,
 * 409 on a second decision, 404 for an unknown ID, and 403 for a customer, even the transfer's own sender.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ComplianceTransferControllerIT {

    private static final String QUEUE = "/api/v1/compliance/transfers";

    @Autowired
    WebApplicationContext context;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    TransferService transfers;

    @Autowired
    IdentityApi identity;

    @Autowired
    JsonMapper json;

    MockMvc mvc;
    Customer sender;
    Customer receiver;
    String officerSubject;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        sender = fixtures.customer(WalletTier.FULL, 6_000_000); // ₹60,000
        receiver = fixtures.customer(WalletTier.FULL, 0);
        officerSubject = ApiMockMvc.newSubject();
    }

    private static RequestPostProcessor officer(String subject) {
        return ApiMockMvc.token(subject, "officer@example.test", "Test Officer", "COMPLIANCE_OFFICER");
    }

    private UUID heldTransfer() {
        TransferResult result = transfers.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byWalletId(receiver.walletId()), Money.parse("50000.00"), "Private note"));
        assertThat(result.status()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
        return result.transferId();
    }

    private static String decide(UUID id, String action) {
        return QUEUE + "/" + id + "/" + action;
    }

    @Test
    void queueListsHeldTransfersOldestFirstWithoutTheNote() throws Exception {
        UUID id = heldTransfer();

        // Other tests leave held transfers behind, so walk every page and look for ours.
        JsonNode found = null;
        Instant previous = Instant.MIN;
        for (int page = 0; found == null; page++) {
            String body = mvc.perform(get(QUEUE).param("page", String.valueOf(page)).param("size", "100")
                            .with(officer(officerSubject)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode root = json.readTree(body);
            JsonNode items = root.get("items");
            assertThat(items.isEmpty()).as("transfer %s not in the queue", id).isFalse();
            for (JsonNode item : items) {
                assertThat(item.get("status").asString()).isEqualTo("HELD_FOR_REVIEW");
                Instant created = Instant.parse(item.get("createdAt").asString());
                assertThat(created).as("oldest first").isAfterOrEqualTo(previous);
                previous = created;
                if (item.get("id").asString().equals(id.toString())) {
                    found = item;
                }
            }
        }
        assertThat(found.get("amount").asString()).isEqualTo("50000.00");
        assertThat(found.get("currency").asString()).isEqualTo("INR");
        assertThat(found.get("senderWalletId").asString()).isEqualTo(sender.walletId().toString());
        assertThat(found.has("note")).as("no personal data in the queue").isFalse();
        assertThat(found.get("reviewedBy").isNull()).isTrue();
    }

    @Test
    void anOfficerApprovesAndIsRecordedAsTheReviewer() throws Exception {
        UUID id = heldTransfer();

        mvc.perform(post(decide(id, "approve")).with(officer(officerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value("50000.00"))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty())
                .andExpect(jsonPath("$.reviewedBy")
                        .value(identity.findUserId(officerSubject).orElseThrow().toString()));

        assertThat(fixtures.wallet(sender.walletId()).balance()).isEqualTo(Money.parse("10000.00"));
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(Money.parse("50000.00"));
        assertThat(fixtures.journalEntriesFor(id)).isEqualTo(1);
    }

    @Test
    void anOfficerRejectsAndTheHoldIsReleased() throws Exception {
        UUID id = heldTransfer();

        mvc.perform(post(decide(id, "reject")).with(officer(officerSubject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedBy").isNotEmpty());

        assertThat(fixtures.wallet(sender.walletId()).held().isZero()).isTrue();
        assertThat(fixtures.wallet(sender.walletId()).balance()).isEqualTo(Money.parse("60000.00"));
        assertThat(fixtures.journalEntriesFor(id)).isZero();
    }

    @Test
    void aSecondDecisionIsAConflict() throws Exception {
        UUID id = heldTransfer();
        mvc.perform(post(decide(id, "approve")).with(officer(officerSubject))).andExpect(status().isOk());

        mvc.perform(post(decide(id, "approve")).with(officer(ApiMockMvc.newSubject())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        mvc.perform(post(decide(id, "reject")).with(officer(ApiMockMvc.newSubject())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        assertThat(fixtures.journalEntriesFor(id)).isEqualTo(1);
    }

    @Test
    void anUnknownTransferIsNotFound() throws Exception {
        mvc.perform(post(decide(UUID.randomUUID(), "approve")).with(officer(officerSubject)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void aCustomerCannotDecideNotEvenOnTheirOwnTransfer() throws Exception {
        UUID id = heldTransfer();

        for (String action : List.of("approve", "reject")) {
            mvc.perform(post(decide(id, action)).with(customer(sender.subject())))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(QUEUE).with(customer(sender.subject()))).andExpect(status().isForbidden());
        assertThat(fixtures.transferStatus(id)).isEqualTo("HELD_FOR_REVIEW");
        assertThat(fixtures.wallet(sender.walletId()).held()).isEqualTo(Money.parse("50000.00"));
    }

    @Test
    void queuePagingIsBounded() throws Exception {
        for (String size : List.of("0", "101")) {
            mvc.perform(get(QUEUE).param("size", size).with(officer(officerSubject)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(QUEUE).param("page", "-1").with(officer(officerSubject)))
                .andExpect(status().isBadRequest());
    }
}
