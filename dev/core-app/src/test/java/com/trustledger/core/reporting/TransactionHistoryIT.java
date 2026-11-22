package com.trustledger.core.reporting;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.reporting.HistoryFixtures.Customer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * FR-S-1 over HTTP: {@code GET /wallets/me/transactions} against PostgreSQL (the V25 view, the V26 indexes). Covers
 * the M11 "done when" list for the history: filters, sort, page boundaries, an empty month, plus the cursor mode and
 * the visibility rules (whose rows, which statuses).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionHistoryIT {

    private static final Instant T0 = Instant.parse("2026-11-10T06:00:00Z");

    @Autowired
    WebApplicationContext context;

    @Autowired
    HistoryFixtures fixtures;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    private ResultActions history(Customer caller, String query) throws Exception {
        return mvc.perform(get("/api/v1/wallets/me/transactions" + query).with(customer(caller.subject())));
    }

    private static Instant minutes(long n) {
        return T0.plusSeconds(60 * n);
    }

    private static UUID other() {
        return UUID.randomUUID();
    }

    // --- content and visibility ---

    @Test
    void anEmptyHistoryIsAnEmptyFirstPage() throws Exception {
        history(fixtures.customer(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void combinesTransfersInAndOutAndTopupsNewestFirst() throws Exception {
        Customer me = fixtures.customer();
        UUID friend = other();
        UUID topup = fixtures.capturedTopup(me.walletId(), 500_000, minutes(1));
        UUID out = fixtures.transfer(me.walletId(), friend, 120_050, "COMPLETED", "dinner", minutes(2));
        UUID in = fixtures.transfer(friend, me.walletId(), 30_000, "COMPLETED", "share", minutes(3));

        history(me, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].id", contains(in.toString(), out.toString(), topup.toString())))
                .andExpect(jsonPath("$.items[0].type").value("TRANSFER_IN"))
                .andExpect(jsonPath("$.items[0].amount").value("300.00"))
                .andExpect(jsonPath("$.items[0].counterpartyWalletId").value(friend.toString()))
                .andExpect(jsonPath("$.items[1].type").value("TRANSFER_OUT"))
                .andExpect(jsonPath("$.items[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[1].amount").value("1200.50"))
                .andExpect(jsonPath("$.items[1].currency").value("INR"))
                .andExpect(jsonPath("$.items[1].note").value("dinner"))
                .andExpect(jsonPath("$.items[1].createdAt").value("2026-11-10T06:02:00Z"))
                .andExpect(jsonPath("$.items[2].type").value("TOPUP"))
                .andExpect(jsonPath("$.items[2].status").value("CAPTURED"))
                .andExpect(jsonPath("$.items[2].counterpartyWalletId").value(nullValue()))
                .andExpect(jsonPath("$.items[2].note").value(nullValue()));
    }

    @Test
    void theSenderSeesEveryStatusTheReceiverOnlyMoneyThatArrived() throws Exception {
        Customer sender = fixtures.customer();
        Customer receiver = fixtures.customer();
        for (String state : List.of("HELD_FOR_REVIEW", "REJECTED", "FAILED", "INITIATED", "COMPLETED", "REVERSED")) {
            fixtures.transfer(sender.walletId(), receiver.walletId(), 10_000, state, null, T0);
        }

        history(sender, "").andExpect(jsonPath("$.totalElements").value(6));
        history(receiver, "").andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].status").value(
                        org.hamcrest.Matchers.containsInAnyOrder("COMPLETED", "REVERSED")));
    }

    @Test
    void aCreatedTopupWithoutAnOrderIsNotShown() throws Exception {
        Customer me = fixtures.customer();
        fixtures.topup(me.walletId(), 10_000, "CREATED", minutes(1));
        UUID failed = fixtures.topup(me.walletId(), 10_000, "FAILED", minutes(2));
        UUID pending = fixtures.topup(me.walletId(), 10_000, "PENDING", minutes(3));

        history(me, "").andExpect(jsonPath("$.items[*].id", contains(pending.toString(), failed.toString())));
    }

    @Test
    void anotherCustomersRowsAreNeverReturned() throws Exception {
        Customer me = fixtures.customer();
        Customer stranger = fixtures.customer();
        fixtures.capturedTopup(stranger.walletId(), 10_000, T0);
        fixtures.completedTransfer(stranger.walletId(), other(), 5_000, T0);

        history(me, "").andExpect(jsonPath("$.totalElements").value(0));
        history(me, "?cursor=").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void aCustomerWithoutAWalletGetsKycRequired() throws Exception {
        mvc.perform(get("/api/v1/wallets/me/transactions").with(customer(ApiMockMvc.newSubject())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("KYC_REQUIRED"));
    }

    // --- filters ---

    @Test
    void filtersByType() throws Exception {
        Customer me = fixtures.customer();
        UUID topup = fixtures.capturedTopup(me.walletId(), 10_000, minutes(1));
        UUID out = fixtures.completedTransfer(me.walletId(), other(), 1_000, minutes(2));
        UUID in = fixtures.completedTransfer(other(), me.walletId(), 2_000, minutes(3));

        history(me, "?type=TOPUP").andExpect(jsonPath("$.items[*].id", contains(topup.toString())));
        history(me, "?type=TRANSFER_OUT").andExpect(jsonPath("$.items[*].id", contains(out.toString())));
        history(me, "?type=TRANSFER_IN").andExpect(jsonPath("$.items[*].id", contains(in.toString())));
    }

    /** Days are Indian days: 18:30 UTC is midnight in Asia/Kolkata. */
    @Test
    void filtersByIndianCalendarDaysBothEndsIncluded() throws Exception {
        Customer me = fixtures.customer();
        UUID lastOfNinth = fixtures.capturedTopup(me.walletId(), 1_000, Instant.parse("2026-11-09T18:29:59Z"));
        UUID firstOfTenth = fixtures.capturedTopup(me.walletId(), 2_000, Instant.parse("2026-11-09T18:30:00Z"));
        UUID lastOfTenth = fixtures.capturedTopup(me.walletId(), 3_000, Instant.parse("2026-11-10T18:29:59Z"));
        UUID firstOfEleventh = fixtures.capturedTopup(me.walletId(), 4_000, Instant.parse("2026-11-10T18:30:00Z"));

        history(me, "?from=2026-11-10&to=2026-11-10")
                .andExpect(jsonPath("$.items[*].id", contains(lastOfTenth.toString(), firstOfTenth.toString())));
        history(me, "?from=2026-11-11").andExpect(jsonPath("$.items[*].id", contains(firstOfEleventh.toString())));
        history(me, "?to=2026-11-09").andExpect(jsonPath("$.items[*].id", contains(lastOfNinth.toString())));
    }

    @Test
    void anEmptyMonthIsAnEmptyPage() throws Exception {
        Customer me = fixtures.customer();
        fixtures.capturedTopup(me.walletId(), 1_000, T0);

        history(me, "?from=2026-10-01&to=2026-10-31").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?from=2026-11-02&to=2026-11-01", "?from=yesterday", "?to=2026-13-01", "?type=REFUND",
            "?sort=status", "?sort=note,asc", "?sort=amount,up", "?sort=amount,asc,createdAt,desc",
            "?sort=amount&sort=createdAt", "?size=0", "?size=101", "?page=-1", "?cursor=not-a-cursor",
            "?cursor=&sort=amount", "?cursor=&page=1"})
    void badParametersAreValidationProblems(String query) throws Exception {
        history(fixtures.customer(), query).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    // --- sorting ---

    @Test
    void sortsByWhitelistedFieldsWithStableTieBreaks() throws Exception {
        Customer me = fixtures.customer();
        UUID small = fixtures.capturedTopup(me.walletId(), 1_000, minutes(3));
        UUID large = fixtures.capturedTopup(me.walletId(), 9_000, minutes(1));
        UUID middle = fixtures.completedTransfer(me.walletId(), other(), 5_000, minutes(2));

        history(me, "?sort=amount,asc")
                .andExpect(jsonPath("$.items[*].id", contains(small.toString(), middle.toString(), large.toString())));
        history(me, "?sort=amount,desc")
                .andExpect(jsonPath("$.items[*].id", contains(large.toString(), middle.toString(), small.toString())));
        history(me, "?sort=amount")
                .andExpect(jsonPath("$.items[*].id", contains(large.toString(), middle.toString(), small.toString())));
        history(me, "?sort=createdAt,asc")
                .andExpect(jsonPath("$.items[*].id", contains(large.toString(), middle.toString(), small.toString())));
        history(me, "?sort=createdAt,desc")
                .andExpect(jsonPath("$.items[*].id", contains(small.toString(), middle.toString(), large.toString())));
    }

    // --- page boundaries ---

    @Test
    void pageBoundaries() throws Exception {
        Customer me = fixtures.customer();
        for (int i = 0; i < 5; i++) {
            fixtures.capturedTopup(me.walletId(), 1_000 + i, minutes(i));
        }

        history(me, "?size=2").andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].amount").value("10.04"))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
        history(me, "?size=2&page=2").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].amount").value("10.00"))
                .andExpect(jsonPath("$.page").value(2));
        history(me, "?size=2&page=3").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(5));
        history(me, "?size=5").andExpect(jsonPath("$.items", hasSize(5))).andExpect(jsonPath("$.totalPages").value(1));
        history(me, "?size=100").andExpect(status().isOk());
    }

    // --- cursor (keyset) mode ---

    /**
     * Rows with equal timestamps are where keyset pagination usually breaks (skipped or repeated rows); the row ID
     * tie-breaker must make the walk visit every row exactly once, in the same order as offset paging.
     */
    @Test
    void theCursorWalkVisitsEveryRowOnceInTheOffsetOrder() throws Exception {
        Customer me = fixtures.customer();
        for (int i = 0; i < 7; i++) {
            Instant at = minutes(i / 3); // groups of three rows share a timestamp
            if (i % 2 == 0) {
                fixtures.capturedTopup(me.walletId(), 1_000, at);
            } else {
                fixtures.completedTransfer(me.walletId(), other(), 1_000, at);
            }
        }
        List<String> byOffset = JsonPath.read(history(me, "?size=100").andReturn().getResponse()
                .getContentAsString(), "$.items[*].id");

        List<String> byCursor = new ArrayList<>();
        String cursor = "";
        int slices = 0;
        do {
            String body = history(me, "?size=3&cursor=" + cursor).andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(nullValue()))
                    .andExpect(jsonPath("$.totalElements").value(nullValue()))
                    .andReturn().getResponse().getContentAsString();
            byCursor.addAll(JsonPath.read(body, "$.items[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
            slices++;
        } while (cursor != null && slices < 10);

        assertThat(byOffset).hasSize(7).doesNotHaveDuplicates();
        assertThat(byCursor).containsExactlyElementsOf(byOffset);
        assertThat(slices).isEqualTo(3);
    }

    @Test
    void cursorModeAppliesTheFilters() throws Exception {
        Customer me = fixtures.customer();
        fixtures.capturedTopup(me.walletId(), 1_000, minutes(1));
        UUID out = fixtures.completedTransfer(me.walletId(), other(), 1_000, minutes(2));

        history(me, "?cursor=&type=TRANSFER_OUT")
                .andExpect(jsonPath("$.items[*].id", contains(out.toString())))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    /** Rows created after the walk started never shift it (they are newer than every cursor). */
    @Test
    void newRowsDoNotShiftACursorWalk() throws Exception {
        Customer me = fixtures.customer();
        UUID first = fixtures.capturedTopup(me.walletId(), 1_000, minutes(2));
        UUID second = fixtures.capturedTopup(me.walletId(), 1_000, minutes(1));

        String body = history(me, "?size=1&cursor=").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].id")).containsExactly(first.toString());
        fixtures.capturedTopup(me.walletId(), 1_000, minutes(3)); // arrives between two slices

        history(me, "?size=1&cursor=" + JsonPath.read(body, "$.nextCursor"))
                .andExpect(jsonPath("$.items[*].id", contains(second.toString())));
    }
}
