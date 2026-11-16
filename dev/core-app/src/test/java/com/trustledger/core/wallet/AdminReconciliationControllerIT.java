package com.trustledger.core.wallet;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.ApiMockMvc.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.wallet.application.ReconciliationJob;
import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;

/** {@code POST /api/v1/admin/reconciliation/run} over HTTP: ADMIN only (SPEC §5). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AdminReconciliationControllerIT {

    private static final String RUN = "/api/v1/admin/reconciliation/run";

    @Autowired
    WebApplicationContext context;

    @Autowired
    LockProvider lockProvider;

    @Autowired
    Clock clock;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    private static RequestPostProcessor as(String role) {
        return token(ApiMockMvc.newSubject(), role.toLowerCase() + "@example.test", "Test " + role, role);
    }

    private ResultActions run(RequestPostProcessor caller) throws Exception {
        return mvc.perform(post(RUN).with(caller));
    }

    @Test
    void adminRunsItAndGetsTheReport() throws Exception {
        run(as("ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.walletsChecked").isNumber())
                .andExpect(jsonPath("$.mismatches").isNumber())
                .andExpect(jsonPath("$.unlinked").isNumber())
                .andExpect(jsonPath("$.listed").isArray())
                .andExpect(jsonPath("$.truncated").isBoolean())
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.finishedAt").isString());
    }

    @Test
    void runInProgressIsAConflictProblem() throws Exception {
        SimpleLock held = lockProvider.lock(new LockConfiguration(clock.instant(), ReconciliationJob.LOCK_NAME,
                Duration.ofMinutes(5), Duration.ZERO)).orElseThrow();
        try {
            run(as("ADMIN")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_IN_PROGRESS"));
        } finally {
            held.unlock();
        }
    }

    @Test
    void otherRolesAreForbidden() throws Exception {
        run(customer(ApiMockMvc.newSubject())).andExpect(status().isForbidden());
        run(as("COMPLIANCE_OFFICER")).andExpect(status().isForbidden());
        run(as("AUDITOR")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(post(RUN)).andExpect(status().isUnauthorized());
    }
}
