package com.trustledger.core.payments.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.shared.security.SecurityConfig;
import com.trustledger.core.shared.web.PingController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The local profile's {@code trustledger.payments.topups.checkout-page=true}: the manual Razorpay Checkout page is
 * served without a token, with a CSP that allows Razorpay's script and frames, and the API is not loosened.
 * (Without the flag, {@code SecurityMatrixIT} shows {@code /checkout.html} is denied.) Slice test, like
 * {@code SwaggerPublicLocalTest}.
 */
@WebMvcTest(controllers = PingController.class, properties = "trustledger.payments.topups.checkout-page=true")
@Import({SecurityConfig.class, CheckoutPageConfig.class})
class CheckoutPageLocalTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void pageAndScriptNeedNoToken() throws Exception {
        mvc.perform(get("/checkout.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("https://checkout.razorpay.com/v1/checkout.js")))
                .andExpect(content().string(containsString("<script src=\"/checkout.js\"></script>")));
        mvc.perform(get("/checkout.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("new Razorpay(")))
                .andExpect(content().string(containsString("Idempotency-Key")));
    }

    @Test
    void pageAllowsRazorpayButNoInlineScript() throws Exception {
        mvc.perform(get("/checkout.html"))
                .andExpect(header().string("Content-Security-Policy", containsString(
                        "script-src 'self' https://checkout.razorpay.com")))
                .andExpect(header().string("Content-Security-Policy", containsString(
                        "frame-src https://api.razorpay.com")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("script-src 'self' 'unsafe-inline'"))))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void onlyThePageIsOpenedNotTheApiOrOtherFiles() throws Exception {
        mvc.perform(get("/api/v1/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/checkout.css")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
