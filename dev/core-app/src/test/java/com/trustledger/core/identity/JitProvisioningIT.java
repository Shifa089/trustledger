package com.trustledger.core.identity;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** FR-ID-2 just-in-time provisioning, against real PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JitProvisioningIT {

    private static final int THREADS = 16;

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserProvisioningService provisioning;

    @Autowired
    IdentityApi identityApi;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    @Test
    void firstAuthenticatedRequestToAnyEndpointCreatesTheUser() throws Exception {
        String subject = ApiMockMvc.newSubject();
        assertThat(identityApi.findUserId(subject)).isEmpty();

        mvc.perform(get("/api/v1/ping").with(customer(subject))).andExpect(status().isOk());

        assertThat(identityApi.findUserId(subject)).isPresent();
        assertThat(rowsFor(subject)).isEqualTo(1);
    }

    @Test
    void laterRequestsReuseTheSameRow() throws Exception {
        String subject = ApiMockMvc.newSubject();
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/ping").with(customer(subject))).andExpect(status().isOk());
        }

        assertThat(rowsFor(subject)).isEqualTo(1);
    }

    @Test
    void unauthenticatedRequestCreatesNothing() throws Exception {
        Integer before = jdbc.queryForObject("select count(*) from identity.users", Integer.class);

        mvc.perform(get("/api/v1/ping")).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select count(*) from identity.users", Integer.class)).isEqualTo(before);
    }

    /**
     * The race: many "first requests" for the same new subject at exactly the same moment. The unique
     * constraint lets one insert win; the others re-read. Exactly one row, and everyone gets its ID.
     * Repeated because a race may not happen on every run; it must never fail.
     */
    @RepeatedTest(5)
    void concurrentFirstRequestsCreateExactlyOneUser() throws Exception {
        String subject = ApiMockMvc.newSubject();
        TokenIdentity identity = new TokenIdentity(subject, "race@example.test", "Race Condition");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return provisioning.ensureUser(identity);
                }));
            }
            start.countDown(); // release all threads at once
            Set<UUID> ids = results.stream().map(JitProvisioningIT::await).collect(Collectors.toSet());

            assertThat(ids).hasSize(1);
            assertThat(rowsFor(subject)).isEqualTo(1);
            assertThat(identityApi.findUserId(subject)).contains(ids.iterator().next());
        }
    }

    private int rowsFor(String subject) {
        return jdbc.queryForObject("select count(*) from identity.users where keycloak_subject = ?", Integer.class,
                subject);
    }

    private static UUID await(Future<UUID> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Provisioning thread failed", e);
        }
    }
}
