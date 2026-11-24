package com.trustledger.core.reporting;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.reporting.HistoryFixtures.Customer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * SPEC §5 "limits used" over HTTP: {@code GET /wallets/me/limit-usage} against PostgreSQL. The rows are placed on
 * the real day and month boundaries (Asia/Kolkata) of the application clock, so the test doesn't depend on the time
 * of day it runs.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitUsageIT {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    WebApplicationContext context;

    @Autowired
    HistoryFixtures fixtures;

    @Autowired
    Clock clock;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    private ResultActions usage(Customer caller) throws Exception {
        return mvc.perform(get("/api/v1/wallets/me/limit-usage").with(customer(caller.subject())));
    }

    @Test
    void aNewWalletHasUsedNothing() throws Exception {
        usage(fixtures.customer()).andExpect(status().isOk())
                .andExpect(jsonPath("$.transferredToday").value("0.00"))
                .andExpect(jsonPath("$.transferredThisMonth").value("0.00"))
                .andExpect(jsonPath("$.addedThisMonth").value("0.00"));
    }

    @Test
    void countsLikeTheLimitChecks() throws Exception {
        Customer me = fixtures.customer();
        UUID friend = UUID.randomUUID();
        LocalDate today = clock.instant().atZone(INDIA).toLocalDate();
        Instant dayStart = today.atStartOfDay(INDIA).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(INDIA).toInstant();
        Instant earlierThisMonth = dayStart.isAfter(monthStart) ? dayStart.minusSeconds(1) : null;

        // Today (the day start is included): completed and held count, rejected doesn't, incoming doesn't.
        fixtures.transfer(me.walletId(), friend, 10_000, "COMPLETED", "a", dayStart);
        fixtures.transfer(me.walletId(), friend, 5_050, "HELD_FOR_REVIEW", "b", dayStart);
        fixtures.transfer(me.walletId(), friend, 99_900, "REJECTED", "c", dayStart);
        fixtures.transfer(friend, me.walletId(), 77_700, "COMPLETED", "d", dayStart);
        // Last month: never counted.
        fixtures.transfer(me.walletId(), friend, 88_800, "COMPLETED", "e", monthStart.minusSeconds(1));
        fixtures.capturedTopup(me.walletId(), 66_600, monthStart.minusSeconds(1));
        // Top-ups this month: open and captured count, failed doesn't.
        fixtures.topup(me.walletId(), 100_000, "CAPTURED", monthStart);
        fixtures.topup(me.walletId(), 20_000, "PENDING", dayStart);
        fixtures.topup(me.walletId(), 3_000, "CREATED", dayStart);
        fixtures.topup(me.walletId(), 55_500, "FAILED", dayStart);
        long monthOnly = 0;
        if (earlierThisMonth != null) {
            // Yesterday or earlier this month: the month counts it, the day doesn't.
            fixtures.transfer(me.walletId(), friend, 1_000, "COMPLETED", "f", earlierThisMonth);
            monthOnly = 1_000;
        }

        usage(me).andExpect(status().isOk())
                .andExpect(jsonPath("$.transferredToday").value("150.50"))
                .andExpect(jsonPath("$.transferredThisMonth").value(monthOnly == 0 ? "150.50" : "160.50"))
                .andExpect(jsonPath("$.addedThisMonth").value("1230.00"));
    }

    @Test
    void anotherCustomersPaymentsNeverShow() throws Exception {
        Customer me = fixtures.customer();
        Customer other = fixtures.customer();
        Instant now = clock.instant();
        fixtures.completedTransfer(other.walletId(), me.walletId(), 40_000, now);
        fixtures.capturedTopup(other.walletId(), 40_000, now);

        usage(me).andExpect(status().isOk())
                .andExpect(jsonPath("$.transferredToday").value("0.00"))
                .andExpect(jsonPath("$.addedThisMonth").value("0.00"));
    }

    @Test
    void withoutAWalletItIsKycRequired() throws Exception {
        mvc.perform(get("/api/v1/wallets/me/limit-usage").with(customer(ApiMockMvc.newSubject())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("KYC_REQUIRED"));
    }
}
