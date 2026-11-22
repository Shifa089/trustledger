package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.audit.AuditRows;
import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import com.trustledger.core.wallet.WalletTier;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-A-1 (Day 20): approving and rejecting a held transfer is audited with the officer, the request's origin and
 * the transfer's status, amount and reviewer before and after; never the sender's note. A second decision is
 * recorded as REJECTED.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferReviewAuditIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    TransferService transfers;

    @Autowired
    IdentityApi identity;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    MockMvc mvc;
    AuditRows rows;
    Customer sender;
    Customer receiver;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        rows = new AuditRows(jdbc, json);
        sender = fixtures.customer(WalletTier.FULL, 6_000_000); // ₹60,000
        receiver = fixtures.customer(WalletTier.FULL, 0);
    }

    private UUID heldTransfer() {
        TransferResult result = transfers.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byWalletId(receiver.walletId()), Money.parse("50000.00"), "Private note"));
        assertThat(result.status()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
        return result.transferId();
    }

    private ResultActions decide(String officerSubject, UUID transferId, String action, String correlationId)
            throws Exception {
        return mvc.perform(post("/api/v1/compliance/transfers/{id}/" + action, transferId)
                .with(ApiMockMvc.token(officerSubject, "officer@example.test", "Test Officer", "COMPLIANCE_OFFICER"))
                .with(r -> {
                    r.setRemoteAddr("198.51.100.20");
                    return r;
                })
                .header(HttpHeaders.USER_AGENT, "Officer-Browser/1.0")
                .header(CorrelationIdFilter.HEADER, correlationId));
    }

    @Test
    void anApprovalIsAuditedNextToTheTransferCompletedFact() throws Exception {
        UUID transferId = heldTransfer();
        String officer = ApiMockMvc.newSubject();

        decide(officer, transferId, "approve", "transfer-approve-1").andExpect(status().isOk());

        UUID officerId = identity.findUserId(officer).orElseThrow();
        AuditEvent approve = rows.single(transferId, "TRANSFER_APPROVE").event();
        assertThat(approve.actorId()).isEqualTo(officerId);
        assertThat(approve.actorRole()).isEqualTo("COMPLIANCE_OFFICER");
        assertThat(approve.entityType()).isEqualTo("TRANSFER");
        assertThat(approve.ip()).isEqualTo("198.51.100.20");
        assertThat(approve.userAgent()).isEqualTo("Officer-Browser/1.0");
        assertThat(approve.correlationId()).isEqualTo("transfer-approve-1");
        assertThat(approve.before()).isEqualTo(Map.of("status", "HELD_FOR_REVIEW", "amount", "50000.00",
                "currency", "INR"));
        assertThat(approve.after()).isEqualTo(Map.of("status", "COMPLETED", "amount", "50000.00",
                "currency", "INR", "reviewedBy", officerId.toString(), "outcome", "SUCCEEDED"));

        AuditRows.Row completed = rows.single(transferId, "TRANSFER_COMPLETED");
        assertThat(completed.event().correlationId()).isEqualTo("transfer-approve-1");
        assertThat(completed.seq()).isLessThan(rows.single(transferId, "TRANSFER_APPROVE").seq());
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where entity_id = ? and "
                + "(before::text like '%Private note%' or after::text like '%Private note%')", Integer.class,
                transferId)).isZero();
        rows.assertChainIntact();
    }

    @Test
    void aRejectionIsAuditedAndASecondDecisionIsRecordedAsRefused() throws Exception {
        UUID transferId = heldTransfer();
        String officer = ApiMockMvc.newSubject();

        decide(officer, transferId, "reject", "transfer-reject-1").andExpect(status().isOk());
        decide(officer, transferId, "approve", "transfer-approve-late").andExpect(status().isConflict());

        AuditEvent reject = rows.single(transferId, "TRANSFER_REJECT").event();
        assertThat(reject.before()).containsEntry("status", "HELD_FOR_REVIEW");
        assertThat(reject.after()).containsEntry("status", "REJECTED").containsEntry("outcome", "SUCCEEDED");

        AuditEvent late = rows.single(transferId, "TRANSFER_APPROVE").event();
        assertThat(late.correlationId()).isEqualTo("transfer-approve-late");
        assertThat(late.before()).containsEntry("status", "REJECTED");
        assertThat(late.after()).isEqualTo(Map.of("outcome", "REJECTED", "errorCode", "INVALID_STATE_TRANSITION"));
    }
}
