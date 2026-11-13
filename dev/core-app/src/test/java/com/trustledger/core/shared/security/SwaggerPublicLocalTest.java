package com.trustledger.core.shared.security;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The local profile's {@code trustledger.security.swagger-public=true}: Swagger needs no token, and the
 * relaxed swagger chain does not loosen the API. (Without the flag, {@code SecurityMatrixIT} shows
 * {@code /v3/api-docs} needs a token.) Slice test: springdoc is not loaded, so a permitted request
 * reaches MVC and gets 404 instead of the 401 the security chain would give.
 */
@WebMvcTest(properties = "trustledger.security.swagger-public=true")
@Import(SecurityConfig.class)
class SwaggerPublicLocalTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void swaggerPathsNeedNoToken() throws Exception {
        for (String path : new String[] {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
                "/swagger-ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().is(not(401))).andExpect(status().is(not(403)));
        }
    }

    @Test
    void swaggerChainDoesNotUseTheStrictApiCsp() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(header().doesNotExist("Content-Security-Policy"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void apiStillNeedsAToken() throws Exception {
        mvc.perform(get("/api/v1/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
