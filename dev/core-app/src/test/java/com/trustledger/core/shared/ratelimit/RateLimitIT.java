package com.trustledger.core.shared.ratelimit;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * SPEC M2 "done when": the 21st transfer-endpoint request in a minute gets 429. Real Redis (Testcontainers),
 * production limits from application.yml (20 money, 100 others). Each test uses fresh subjects, so the
 * buckets of other tests in the shared Redis do not interfere.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimitIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    StringRedisTemplate redis;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    @Test
    void twentyFirstTransferInAMinuteIsRateLimited() throws Exception {
        String subject = ApiMockMvc.newSubject();
        for (int i = 1; i <= 20; i++) {
            mvc.perform(post("/api/v1/transfers").with(customer(subject)))
                    .andExpect(status().isNotImplemented())
                    .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, String.valueOf(20 - i)));
        }

        mvc.perform(post("/api/v1/transfers").with(customer(subject)).header(CorrelationIdFilter.HEADER, "rl-429"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "0"))
                .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/rate-limited"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/transfers"))
                .andExpect(jsonPath("$.correlationId").value("rl-429"));
    }

    @Test
    void topupsShareTheMoneyBucketWithTransfers() throws Exception {
        String subject = ApiMockMvc.newSubject();
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/transfers").with(customer(subject))).andExpect(status().isNotImplemented());
            mvc.perform(post("/api/v1/topups").with(customer(subject))).andExpect(status().isNotImplemented());
        }

        mvc.perform(post("/api/v1/topups").with(customer(subject))).andExpect(status().isTooManyRequests());
    }

    @Test
    void limitIsPerUser() throws Exception {
        String busy = ApiMockMvc.newSubject();
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/transfers").with(customer(busy)));
        }
        mvc.perform(post("/api/v1/transfers").with(customer(busy))).andExpect(status().isTooManyRequests());

        mvc.perform(post("/api/v1/transfers").with(customer(ApiMockMvc.newSubject())))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void exhaustedMoneyBucketDoesNotBlockOtherEndpoints() throws Exception {
        String subject = ApiMockMvc.newSubject();
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/transfers").with(customer(subject)));
        }
        mvc.perform(post("/api/v1/transfers").with(customer(subject))).andExpect(status().isTooManyRequests());

        mvc.perform(get("/api/v1/me").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitFilter.REMAINING_HEADER, "99"));
    }

    @Test
    void hundredAndFirstOrdinaryRequestIsRateLimited() throws Exception {
        String subject = ApiMockMvc.newSubject();
        for (int i = 0; i < 100; i++) {
            mvc.perform(get("/api/v1/ping").with(customer(subject))).andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/ping").with(customer(subject)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    @Test
    void unauthenticatedRequestsDoNotSpendAnyBudget() throws Exception {
        mvc.perform(post("/api/v1/transfers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void bucketsLiveInRedisAndExpire() throws Exception {
        String subject = ApiMockMvc.newSubject();
        mvc.perform(post("/api/v1/transfers").with(customer(subject))).andExpect(status().isNotImplemented());

        String key = "rl:money:" + subject;
        assertThat(redis.hasKey(key)).isTrue();
        // Expires once the bucket would be full again (about a minute), so idle users leave nothing behind.
        assertThat(redis.getExpire(key)).isPositive().isLessThanOrEqualTo(120);
    }
}
