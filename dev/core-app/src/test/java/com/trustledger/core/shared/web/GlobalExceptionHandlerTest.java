package com.trustledger.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Proves the ProblemDetail error format from SPEC §5 end to end through MockMvc. */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ErrorTestController.class)
@Import(GlobalExceptionHandlerTest.ErrorTestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void businessErrorHasFullProblemShape() throws Exception {
        mvc.perform(post("/api/v1/test/transfer").header(CorrelationIdFilter.HEADER, "corr-42"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-42"))
                .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/insufficient-funds"))
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Available balance is lower than the transfer amount."))
                .andExpect(jsonPath("$.instance").value("/api/v1/test/transfer"))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.correlationId").value("corr-42"));
    }

    @Test
    void generatedCorrelationIdInBodyMatchesResponseHeader() throws Exception {
        var response = mvc.perform(post("/api/v1/test/transfer")).andReturn().getResponse();
        String echoed = response.getHeader(CorrelationIdFilter.HEADER);

        assertThat(echoed).isNotBlank();
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.correlationId")).isEqualTo(echoed);
    }

    @Test
    void bodyValidationListsViolationsWithoutRejectedValues() throws Exception {
        mvc.perform(post("/api/v1/test/kyc").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"SECRETPAN1\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("https://trustledger.dev/errors/validation-failed"))
                .andExpect(jsonPath("$.violations", hasSize(2)))
                .andExpect(jsonPath("$.violations[?(@.field == 'pan')].message").value("must be a valid PAN"))
                .andExpect(jsonPath("$.violations[?(@.field == 'name')]").exists())
                .andExpect(content().string(not(containsString("SECRETPAN1"))));
    }

    @Test
    void parameterValidationListsViolations() throws Exception {
        mvc.perform(get("/api/v1/test/page").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("size"));
    }

    @Test
    void malformedJsonIsValidationFailure() throws Exception {
        mvc.perform(post("/api/v1/test/kyc").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/test/does-not-exist"));
    }

    @Test
    void wrongMethodIsUnsupportedRequestWith405() throws Exception {
        mvc.perform(get("/api/v1/test/transfer"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_REQUEST"));
    }

    @Test
    void accessDeniedIs403() throws Exception {
        mvc.perform(get("/api/v1/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void unexpectedErrorIsGeneric500WithoutLeakingMessage() throws Exception {
        mvc.perform(get("/api/v1/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.GENERIC_DETAIL))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("db password"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    record KycRequest(@Pattern(regexp = "[A-Z]{5}[0-9]{4}[A-Z]", message = "must be a valid PAN") String pan,
                      @NotBlank String name) {
    }

    @RestController
    static class ErrorTestController {

        @PostMapping("/test/transfer")
        void transfer() {
            throw new TrustLedgerException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Available balance is lower than the transfer amount.");
        }

        @PostMapping("/test/kyc")
        void kyc(@Valid @RequestBody KycRequest request) {
        }

        @GetMapping("/test/page")
        void page(@RequestParam @Min(1) int size) {
        }

        @GetMapping("/test/forbidden")
        void forbidden() {
            throw new AccessDeniedException("no");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("db password is hunter2");
        }
    }

    /** Real security rules arrive in M2; here we only test the error format. */
    @TestConfiguration
    static class PermitAllSecurity {

        @Bean
        SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .csrf(c -> c.disable())
                    .build();
        }
    }
}
