package com.trustledger.core.privacy;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.audit.application.AuditVerificationService;
import com.trustledger.core.audit.domain.ChainVerification;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.privacy.application.ErasureService;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.application.WalletLedgerLinkService;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import com.trustledger.events.AmlCaseStatusChangedV1;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-PR-2 erasure end to end (M10 "Done when": erasure leaves the ledger intact while the users' personal fields are
 * anonymised): every refusal leaves everything as it was, a retry after success is refused, two racing requests erase
 * once, and the open-AML-case rule follows the AML service's events over Kafka.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ErasureIT {

    private static final String V = "2026-11-01"; // policy versions in application.yml
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserProvisioningService users;

    @Autowired
    IdentityApi identity;

    @Autowired
    WalletProvisioningService provisioning;

    @Autowired
    WalletLedgerLinkService links;

    @Autowired
    WalletApi wallets;

    @Autowired
    LedgerApi ledger;

    @Autowired
    TransferService transfers;

    @Autowired
    ErasureService erasures;

    @Autowired
    AuditVerificationService audit;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaContainer kafka;

    MockMvc mvc;
    String subject;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        subject = ApiMockMvc.newSubject();
    }

    @Test
    void erasureAnonymisesThePersonAndLeavesLedgerTransfersAndAuditUntouched() throws Exception {
        Customer customer = customerWithMinKyc();
        Customer sender = fullCustomerWith(100_000);
        // Money in and back out: the wallet is empty, but it has a history.
        transfers.transfer(new TransferCommand(sender.userId(), TransferRecipient.byWalletId(customer.walletId()),
                Money.parse("150.25"), "Dinner share"));
        provisioning.apply(customer.userId(), KycLevel.FULL); // a MIN wallet can't send P2P
        transfers.transfer(new TransferCommand(customer.userId(), TransferRecipient.byWalletId(sender.walletId()),
                Money.parse("150.25"), null));
        List<Map<String, Object>> transfersBefore = transfersOf(customer.walletId());
        List<Map<String, Object>> journalBefore = journalLinesOf(customer.walletId());
        List<Map<String, Object>> auditBefore = jdbc.queryForList("select * from audit.audit_events order by seq");
        long consentRows = consentCount(customer.userId());
        assertThat(transfersBefore).hasSize(2);
        assertThat(journalBefore).hasSize(2);

        String body = mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.startsWith("no-store")))
                .andExpect(jsonPath("$.type").value("ERASURE"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.download").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // Identity: no personal field, no blind index, status ERASED; the row and its ID stay.
        Map<String, Object> user = jdbc.queryForMap("select * from identity.users where id = ?", customer.userId());
        assertThat(user.get("status")).isEqualTo("ERASED");
        assertThat(user).containsEntry("email_enc", null).containsEntry("email_hash", null)
                .containsEntry("phone_enc", null).containsEntry("phone_hash", null)
                .containsEntry("full_name_enc", null);
        assertThat(identity.findByPhone(customer.phone())).isEmpty();
        assertThat(identity.findNotificationContact(customer.userId())).isEmpty();
        // Consents: still the evidence of what was agreed, but without the IP address.
        assertThat(consentCount(customer.userId())).isEqualTo(consentRows);
        assertThat(jdbc.queryForObject("select count(*) from identity.consents where user_id = ? and ip is not null",
                Long.class, customer.userId())).isZero();
        // Wallet closed; money records exactly as before.
        assertThat(jdbc.queryForObject("select status from wallet.wallets where id = ?", String.class,
                customer.walletId())).isEqualTo("CLOSED");
        assertThat(transfersOf(customer.walletId())).isEqualTo(transfersBefore);
        assertThat(journalLinesOf(customer.walletId())).isEqualTo(journalBefore);
        // Audit: every earlier event untouched, one new erasure event with IDs only, and the chain still verifies.
        List<Map<String, Object>> auditAfter = jdbc.queryForList("select * from audit.audit_events order by seq");
        assertThat(auditAfter.subList(0, auditBefore.size())).isEqualTo(auditBefore);
        Map<String, Object> erased = jdbc.queryForMap("""
                select * from audit.audit_events where action = 'PERSONAL_DATA_ERASED' and entity_id = ?""",
                customer.userId());
        assertThat(erased.get("entity_type")).isEqualTo("USER");
        assertThat(erased.get("actor_id")).isEqualTo(customer.userId());
        assertThat(erased.get("after").toString()).contains("ERASED").contains(customer.walletId().toString())
                .contains("CLOSED").doesNotContain("customer.one").doesNotContain(customer.phone())
                .doesNotContain("Test Customer");
        assertThat(audit.verify().status()).isEqualTo(ChainVerification.Status.OK);
        // The request is listed like an export, without a download.
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        mvc.perform(get("/api/v1/me/data-requests").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].type").value("ERASURE"));
    }

    @Test
    void aSecondErasureIsRefusedAndChangesNothing() throws Exception {
        Customer customer = customerWithMinKyc();
        mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject))).andExpect(status().isOk());
        Map<String, Object> userAfterFirst = userRow(customer.userId());

        mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

        assertThat(userRow(customer.userId())).isEqualTo(userAfterFirst);
        assertThat(erasureCount(customer.userId())).isEqualTo(1);
        assertThat(auditCount("PERSONAL_DATA_ERASED", customer.userId())).isEqualTo(1);
    }

    @Test
    void aWalletWithMoneyIsRefusedClearlyAndNothingChanges() throws Exception {
        Customer customer = customerWithMinKyc();
        Customer sender = fullCustomerWith(100_000);
        transfers.transfer(new TransferCommand(sender.userId(), TransferRecipient.byWalletId(customer.walletId()),
                Money.parse("10.00"), null));
        Map<String, Object> before = userRow(customer.userId());

        mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WALLET_NOT_EMPTY"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("10.00")));

        assertUntouched(customer, before);
    }

    @Test
    void aTopupThatMayStillBePaidIsRefused() throws Exception {
        Customer customer = customerWithMinKyc();
        insertPendingTopup(customer.walletId());
        Map<String, Object> before = userRow(customer.userId());

        mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WALLET_NOT_EMPTY"));

        assertUntouched(customer, before);
    }

    @Test
    void aFrozenWalletIsRefused() throws Exception {
        Customer customer = customerWithMinKyc();
        wallets.freezeForAml(UUID.randomUUID(), customer.walletId(), UUID.randomUUID(), "R2");
        Map<String, Object> before = userRow(customer.userId());

        mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WALLET_FROZEN"));

        assertThat(userRow(customer.userId())).isEqualTo(before);
        assertThat(erasureCount(customer.userId())).isZero();
    }

    @Test
    void anOpenAmlCaseBlocksErasureUntilTheAmlServiceReportsItFinished() throws Exception {
        Customer customer = customerWithMinKyc();
        UUID caseId = UUID.randomUUID();
        Map<String, Object> before = userRow(customer.userId());
        try (KafkaProducer<String, byte[]> producer = producer()) {
            send(producer, new AmlCaseStatusChangedV1(UUID.randomUUID(), Instant.now(), customer.walletId(), caseId,
                    "OPEN", true));
            eventually(() -> assertThat(projectionOpen(caseId)).isTrue());

            mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("AML_CASE_OPEN"));
            assertUntouched(customer, before);

            send(producer, new AmlCaseStatusChangedV1(UUID.randomUUID(), Instant.now(), customer.walletId(), caseId,
                    "CLOSED_FALSE_POSITIVE", false));
            eventually(() -> assertThat(projectionOpen(caseId)).isFalse());
        }

        mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject))).andExpect(status().isOk());
    }

    @Test
    void aUserWithoutAWalletIsErasedToo() throws Exception {
        mvc.perform(get("/api/v1/me/data-requests").with(customer(subject))).andExpect(status().isOk());
        UUID userId = identity.findUserId(subject).orElseThrow();

        mvc.perform(post("/api/v1/me/erasure-request").with(customer(subject))).andExpect(status().isOk());

        assertThat(userRow(userId).get("status")).isEqualTo("ERASED");
        assertThat(jdbc.queryForObject("""
                select after::text from audit.audit_events where action = 'PERSONAL_DATA_ERASED' and entity_id = ?""",
                String.class, userId)).doesNotContain("walletId");
    }

    @Test
    void twoRacingRequestsEraseOnce() throws Exception {
        Customer customer = customerWithMinKyc();
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> erase = () -> {
            start.await();
            try {
                erasures.requestErasure(customer.userId());
                return "OK";
            } catch (TrustLedgerException e) {
                return e.errorCode().name();
            }
        };
        List<String> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = List.of(pool.submit(erase), pool.submit(erase));
            start.countDown();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }
        }

        assertThat(outcomes).containsExactlyInAnyOrder("OK", ErrorCode.INVALID_STATE_TRANSITION.name());
        assertThat(erasureCount(customer.userId())).isEqualTo(1);
        assertThat(auditCount("PERSONAL_DATA_ERASED", customer.userId())).isEqualTo(1);
    }

    // --- helpers ---

    record Customer(UUID userId, UUID walletId, String phone) {
    }

    /** The caller: consents (with an IP) and minimum KYC over HTTP; waits for the wallet and its ledger account. */
    private Customer customerWithMinKyc() throws Exception {
        mvc.perform(post("/api/v1/me/consents").with(customer(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"consents":[{"purpose":"TERMS","policyVersion":"%s","granted":true},
                                     {"purpose":"PRIVACY","policyVersion":"%s","granted":true}]}""".formatted(V, V)))
                .andExpect(status().isCreated());
        String phone = randomPhone();
        mvc.perform(post("/api/v1/kyc/min").with(customer(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Erasure Test Person","dateOfBirth":"1990-02-03","mobile":"%s","pan":"ABCPE1234F"}"""
                        .formatted(phone)))
                .andExpect(status().isCreated());
        UUID userId = identity.findUserId(subject).orElseThrow();
        UUID[] walletId = new UUID[1];
        eventually(() -> {
            walletId[0] = wallets.findByUser(userId).map(w -> w.walletId()).orElse(null);
            assertThat(walletId[0]).isNotNull();
            assertThat(jdbc.queryForObject("select ledger_account_id from wallet.wallets where id = ?", UUID.class,
                    walletId[0])).isNotNull();
        });
        assertThat(jdbc.queryForObject("select count(*) from identity.consents where user_id = ? and ip is not null",
                Long.class, userId)).isPositive();
        return new Customer(userId, walletId[0], phone);
    }

    /** Another FULL-tier customer with money in a ledger-consistent wallet. */
    private Customer fullCustomerWith(long minor) {
        String phone = randomPhone();
        UUID userId = users.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), null, "Sender Person"));
        identity.recordVerifiedPhone(userId, phone);
        provisioning.apply(userId, KycLevel.FULL);
        UUID walletId = wallets.findByUser(userId).orElseThrow().walletId();
        links.link(walletId);
        Money amount = Money.of(minor, Money.INR);
        tx.executeWithoutResult(status -> {
            ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                    PostingLine.debit(AccountCodes.GATEWAY_CLEARING, amount),
                    PostingLine.credit(AccountCodes.wallet(walletId), amount))));
            wallets.lockForUpdate(List.of(walletId));
            wallets.credit(walletId, amount);
        });
        return new Customer(userId, walletId, phone);
    }

    private void assertUntouched(Customer customer, Map<String, Object> userBefore) {
        assertThat(userRow(customer.userId())).isEqualTo(userBefore);
        assertThat(userBefore.get("status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from wallet.wallets where id = ?", String.class,
                customer.walletId())).isNotEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from identity.consents where user_id = ? and ip is not null",
                Long.class, customer.userId())).isPositive();
        assertThat(erasureCount(customer.userId())).isZero();
        assertThat(auditCount("PERSONAL_DATA_ERASED", customer.userId())).isZero();
    }

    private Map<String, Object> userRow(UUID userId) {
        return jdbc.queryForMap("select * from identity.users where id = ?", userId);
    }

    private List<Map<String, Object>> transfersOf(UUID walletId) {
        return jdbc.queryForList("""
                select * from payments.transfers where sender_wallet_id = ? or receiver_wallet_id = ? order by id""",
                walletId, walletId);
    }

    private List<Map<String, Object>> journalLinesOf(UUID walletId) {
        UUID account = jdbc.queryForObject("select ledger_account_id from wallet.wallets where id = ?", UUID.class,
                walletId);
        return jdbc.queryForList("select * from ledger.journal_lines where account_id = ? order by id", account);
    }

    private long consentCount(UUID userId) {
        return jdbc.queryForObject("select count(*) from identity.consents where user_id = ?", Long.class, userId);
    }

    private long erasureCount(UUID userId) {
        return jdbc.queryForObject("""
                select count(*) from privacy.data_requests where user_id = ? and type = 'ERASURE'""", Long.class,
                userId);
    }

    private long auditCount(String action, UUID entityId) {
        return jdbc.queryForObject("select count(*) from audit.audit_events where action = ? and entity_id = ?",
                Long.class, action, entityId);
    }

    private Boolean projectionOpen(UUID caseId) {
        List<Boolean> open = jdbc.queryForList("select open from privacy.aml_cases where case_id = ?", Boolean.class,
                caseId);
        return open.isEmpty() ? null : open.getFirst();
    }

    private void insertPendingTopup(UUID walletId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id, status,
                    created_at, updated_at, version)
                values (?, ?, 50000, 'INR', ?, 'PENDING', ?, ?, 0)""",
                id, walletId, "order_" + id.toString().replace("-", "").substring(0, 20), now, now);
    }

    private KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new ByteArraySerializer());
    }

    /** As risk-aml-service's relay sends it: JSON, __TypeId__ and eventId headers, wallet as key. */
    static void send(KafkaProducer<String, byte[]> producer, AmlCaseStatusChangedV1 event) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(AmlCaseStatusChangedV1.TOPIC,
                event.walletId().toString(), JSON.writeValueAsBytes(event));
        record.headers().add("__TypeId__", AmlCaseStatusChangedV1.class.getName().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not send the test record", e);
        }
    }

    private static String randomPhone() {
        return "7" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }
}
