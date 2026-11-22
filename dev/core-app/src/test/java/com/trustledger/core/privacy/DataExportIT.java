package com.trustledger.core.privacy;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
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
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.privacy.application.DataExportProcessor;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.application.WalletLedgerLinkService;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

/**
 * FR-PR-2 data export end to end: HTTP request, asynchronous processing after commit, data collected through each
 * module's API, JSON in S3 (LocalStack), and a presigned download link from {@code GET /me/data-requests}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DataExportIT {

    private static final String PAN = "ABCPX9876K";
    private static final String DOB = "1991-04-17";
    private static final String V = "2026-11-01"; // policy versions in application.yml

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
    DataExportProcessor processor;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    String subject;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
        subject = ApiMockMvc.newSubject();
    }

    @Test
    void exportsProfileConsentsKycWalletTransfersAndTopupsAsJsonBehindATenMinuteLink() throws Exception {
        // The customer: consents, minimum KYC over HTTP (the wallet follows asynchronously), then money in.
        String phone = randomPhone();
        consent();
        mvc.perform(post("/api/v1/kyc/min").with(customer(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Export Test Person","dateOfBirth":"%s","mobile":"%s","pan":"%s"}"""
                        .formatted(DOB, phone, PAN)))
                .andExpect(status().isCreated());
        UUID userId = identity.findUserId(subject).orElseThrow();
        UUID walletId = linkedWalletOf(userId);
        Sender sender = fullSender();
        TransferResult received = transfers.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byWalletId(walletId), Money.parse("150.25"), "Dinner share"));
        UUID topupId = insertPendingTopup(walletId, 50_000);

        String accepted = mvc.perform(post("/api/v1/me/data-export").with(customer(subject)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/me/data-requests"))
                .andExpect(jsonPath("$.type").value("EXPORT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.download").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(JsonPath.read(accepted, "$.id"));

        String listed = completedListing(requestId);
        Instant expiresAt = Instant.parse(JsonPath.read(listed, "$.items[0].download.expiresAt"));
        assertThat(expiresAt).isBetween(Instant.now().plus(Duration.ofMinutes(9)),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
        String url = JsonPath.read(listed, "$.items[0].download.url");
        assertThat(url).contains("X-Amz-Expires=600").contains("exports/" + userId + "/" + requestId + ".json");

        HttpResponse<String> file = download(url);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("attachment"));
        DocumentContext json = JsonPath.parse(file.body());
        assertThat((String) json.read("$.format")).isEqualTo("trustledger-data-export/v1");
        assertThat((String) json.read("$.requestId")).isEqualTo(requestId.toString());
        assertThat((String) json.read("$.userId")).isEqualTo(userId.toString());
        // The customer's own data comes back in clear: that is the point of an export.
        assertThat((String) json.read("$.profile.email")).isEqualTo("customer.one@example.test");
        assertThat((String) json.read("$.profile.phone")).endsWith(phone);
        assertThat((String) json.read("$.profile.fullName")).isEqualTo("Test Customer One");
        assertThat((String) json.read("$.profile.status")).isEqualTo("ACTIVE");
        assertThat((List<String>) json.read("$.consents[*].purpose")).containsExactlyInAnyOrder("TERMS", "PRIVACY");
        assertThat((String) json.read("$.consents[0].ip")).isEqualTo("127.0.0.1");
        assertThat((String) json.read("$.kyc.currentLevel")).isEqualTo("MIN");
        assertThat((List<String>) json.read("$.kyc.cases[*].status")).containsExactly("APPROVED");
        assertThat((String) json.read("$.wallet.walletId")).isEqualTo(walletId.toString());
        assertThat((String) json.read("$.wallet.tier")).isEqualTo("MIN");
        assertThat((String) json.read("$.wallet.balance")).isEqualTo("150.25");
        assertThat((List<Object>) json.read("$.transfers")).hasSize(1);
        assertThat((String) json.read("$.transfers[0].transferId")).isEqualTo(received.transferId().toString());
        assertThat((String) json.read("$.transfers[0].direction")).isEqualTo("RECEIVED");
        assertThat((String) json.read("$.transfers[0].counterpartyWalletId")).isEqualTo(sender.walletId().toString());
        assertThat((String) json.read("$.transfers[0].amount")).isEqualTo("150.25");
        assertThat((String) json.read("$.transfers[0].note")).isEqualTo("Dinner share");
        assertThat((List<Object>) json.read("$.topups")).hasSize(1);
        assertThat((String) json.read("$.topups[0].topupId")).isEqualTo(topupId.toString());
        assertThat((String) json.read("$.topups[0].amount")).isEqualTo("500.00");
        assertThat((String) json.read("$.topups[0].status")).isEqualTo("PENDING");
        // KYC status only: no PAN, date of birth or document, and nothing about the sender but a wallet ID.
        assertThat(file.body()).doesNotContain(PAN).doesNotContain(DOB).doesNotContain("Export Test Person")
                .doesNotContain("s3Key").doesNotContain("documents").doesNotContain(sender.userId().toString())
                .doesNotContain("Sender Person");

        // The row: completed with the file's key; the outbox publication was completed too.
        Map<String, Object> row = jdbc.queryForMap("select * from privacy.data_requests where id = ?", requestId);
        assertThat(row.get("result_s3_key")).isEqualTo("exports/" + userId + "/" + requestId + ".json");
        assertThat(row.get("failure_reason")).isNull();
        eventually(() -> assertThat(jdbc.queryForObject("""
                select count(*) from shared.event_publication
                where event_type like '%DataExportRequested' and serialized_event like ? and completion_date is not null""",
                Long.class, "%" + requestId + "%")).isEqualTo(1));
    }

    @Test
    void aUserWithoutKycGetsAnExportWithoutWalletOrMoneyMovements() throws Exception {
        mvc.perform(post("/api/v1/me/data-export").with(customer(subject))).andExpect(status().isAccepted());
        UUID requestId = requestIdOfLatest();

        String listed = completedListing(requestId);
        DocumentContext json = JsonPath.parse(download(JsonPath.read(listed, "$.items[0].download.url")).body());

        assertThat((String) json.read("$.kyc.currentLevel")).isEqualTo("NONE");
        assertThat((List<Object>) json.read("$.kyc.cases")).isEmpty();
        assertThat((Object) json.read("$.wallet")).isNull();
        assertThat((List<Object>) json.read("$.transfers")).isEmpty();
        assertThat((List<Object>) json.read("$.topups")).isEmpty();
        assertThat((List<Object>) json.read("$.consents")).isEmpty();
    }

    @Test
    void aSecondExportWhileOneIsPendingIs409AndTheListShowsItWithoutALink() throws Exception {
        mvc.perform(get("/api/v1/me/data-requests").with(customer(subject))).andExpect(status().isOk());
        UUID userId = identity.findUserId(subject).orElseThrow();
        UUID pending = insertPendingExport(userId);

        mvc.perform(post("/api/v1/me/data-export").with(customer(subject)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_IN_PROGRESS"));

        mvc.perform(get("/api/v1/me/data-requests").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(pending.toString()))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].download").doesNotExist());
    }

    @Test
    void aFinishedExportCanBeFollowedByANewOneAndTheListIsNewestFirst() throws Exception {
        mvc.perform(post("/api/v1/me/data-export").with(customer(subject))).andExpect(status().isAccepted());
        UUID first = requestIdOfLatest();
        completedListing(first);

        mvc.perform(post("/api/v1/me/data-export").with(customer(subject))).andExpect(status().isAccepted());
        UUID second = requestIdOfLatest();
        assertThat(second).isNotEqualTo(first);

        eventually(() -> {
            try {
                mvc.perform(get("/api/v1/me/data-requests").with(customer(subject)))
                        .andExpect(jsonPath("$.items", hasSize(2)))
                        .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                        .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                        .andExpect(jsonPath("$.items[1].id").value(first.toString()))
                        .andExpect(jsonPath("$.items[1].download.url").exists());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void usersOnlySeeTheirOwnRequests() throws Exception {
        mvc.perform(post("/api/v1/me/data-export").with(customer(subject))).andExpect(status().isAccepted());
        completedListing(requestIdOfLatest());

        String someoneElse = ApiMockMvc.newSubject();
        mvc.perform(get("/api/v1/me/data-requests").with(customer(someoneElse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void processingTheSameRequestAgainChangesNothing() throws Exception {
        mvc.perform(post("/api/v1/me/data-export").with(customer(subject))).andExpect(status().isAccepted());
        UUID requestId = requestIdOfLatest();
        completedListing(requestId);
        Map<String, Object> before = jdbc.queryForMap("select * from privacy.data_requests where id = ?", requestId);

        processor.process(requestId); // a redelivered event after a restart

        assertThat(jdbc.queryForMap("select * from privacy.data_requests where id = ?", requestId))
                .isEqualTo(before);
    }

    @Test
    void theListIsNeverCached() throws Exception {
        mvc.perform(get("/api/v1/me/data-requests").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", startsWith("no-store")));
    }

    // --- helpers ---

    record Sender(UUID userId, UUID walletId) {
    }

    private void consent() throws Exception {
        mvc.perform(post("/api/v1/me/consents").with(customer(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"consents":[{"purpose":"TERMS","policyVersion":"%s","granted":true},
                                     {"purpose":"PRIVACY","policyVersion":"%s","granted":true}]}""".formatted(V, V)))
                .andExpect(status().isCreated());
    }

    /** Waits for the asynchronous wallet creation and ledger link that follow minimum KYC. */
    private UUID linkedWalletOf(UUID userId) {
        UUID[] walletId = new UUID[1];
        eventually(() -> {
            walletId[0] = wallets.findByUser(userId).map(w -> w.walletId()).orElse(null);
            assertThat(walletId[0]).isNotNull();
            assertThat(jdbc.queryForObject("select ledger_account_id from wallet.wallets where id = ?", UUID.class,
                    walletId[0])).isNotNull();
        });
        return walletId[0];
    }

    /** A FULL-tier customer with ₹1,000 in a ledger-consistent wallet. */
    private Sender fullSender() {
        UUID userId = users.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), null, "Sender Person"));
        identity.recordVerifiedPhone(userId, randomPhone());
        provisioning.apply(userId, KycLevel.FULL);
        UUID walletId = wallets.findByUser(userId).orElseThrow().walletId();
        links.link(walletId);
        Money amount = Money.of(100_000, Money.INR);
        tx.executeWithoutResult(status -> {
            ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                    PostingLine.debit(AccountCodes.GATEWAY_CLEARING, amount),
                    PostingLine.credit(AccountCodes.wallet(walletId), amount))));
            wallets.lockForUpdate(List.of(walletId));
            wallets.credit(walletId, amount);
        });
        return new Sender(userId, walletId);
    }

    private UUID insertPendingTopup(UUID walletId, long minor) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id, status,
                    created_at, updated_at, version)
                values (?, ?, ?, 'INR', ?, 'PENDING', ?, ?, 0)""",
                id, walletId, minor, "order_" + id.toString().replace("-", "").substring(0, 20), Timestamp.from(now),
                Timestamp.from(now));
        return id;
    }

    private UUID insertPendingExport(UUID userId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into privacy.data_requests (id, user_id, type, status, requested_at, created_at, updated_at,
                    version)
                values (?, ?, 'EXPORT', 'PENDING', ?, ?, ?, 0)""", id, userId, now, now, now);
        return id;
    }

    private UUID requestIdOfLatest() {
        UUID userId = identity.findUserId(subject).orElseThrow();
        return jdbc.queryForObject("select id from privacy.data_requests where user_id = ?"
                + " order by created_at desc, id desc limit 1", UUID.class, userId);
    }

    /** Polls the list until the request is COMPLETED; returns the response body with it as the first item. */
    private String completedListing(UUID requestId) {
        String[] body = new String[1];
        eventually(() -> {
            try {
                body[0] = mvc.perform(get("/api/v1/me/data-requests").with(customer(subject)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].id").value(requestId.toString()))
                        .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                        .andExpect(jsonPath("$.items[0].completedAt").exists())
                        .andExpect(jsonPath("$.items[0].download.url").exists())
                        .andReturn().getResponse().getContentAsString();
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        return body[0];
    }

    private static HttpResponse<String> download(String url) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String randomPhone() {
        return "8" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }
}
