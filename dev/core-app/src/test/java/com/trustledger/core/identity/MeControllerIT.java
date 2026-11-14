package com.trustledger.core.identity;

import static com.trustledger.core.ApiMockMvc.customer;
import static com.trustledger.core.ApiMockMvc.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET /api/v1/me}: own profile, created on first call, personal data masked (FR-ID-2, FR-PR-1). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MeControllerIT {

    @Autowired
    WebApplicationContext context;

    @Autowired
    IdentityApi identityApi;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiMockMvc.create(context);
    }

    @Test
    void returnsMaskedProfileAndCreatesTheUser() throws Exception {
        String subject = ApiMockMvc.newSubject();

        String body = mvc.perform(get("/api/v1/me").with(customer(subject)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(identityApi.findUserId(subject).orElseThrow().toString()))
                .andExpect(jsonPath("$.email").value("c***@example.test"))
                .andExpect(jsonPath("$.fullName").value("T*** C*** O***"))
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles", contains("CUSTOMER")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("customer.one", "Customer One", "Test Customer", subject);
    }

    @Test
    void staffMayCallMeToo() throws Exception {
        mvc.perform(get("/api/v1/me").with(token(ApiMockMvc.newSubject(), "officer@example.test", "Officer Person",
                        "COMPLIANCE_OFFICER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("o***@example.test"))
                .andExpect(jsonPath("$.roles", contains("COMPLIANCE_OFFICER")));
    }

    @Test
    void tokenWithoutEmailOrNameStillWorks() throws Exception {
        mvc.perform(get("/api/v1/me").with(token(ApiMockMvc.newSubject(), null, null, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.fullName").value(nullValue()));
    }

    @Test
    void sameUserGetsTheSameIdOnEveryCall() throws Exception {
        String subject = ApiMockMvc.newSubject();
        String first = mvc.perform(get("/api/v1/me").with(customer(subject)))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get("/api/v1/me").with(customer(subject)))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }
}
