package com.trustledger.core.shared.security;

import static com.trustledger.core.KeycloakTokens.DEV_CLIENT;
import static com.trustledger.core.KeycloakTokens.WEB_CLIENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.KeycloakContainer;
import com.trustledger.core.KeycloakTokens;
import com.trustledger.core.KeycloakTokens.TokenResponse;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Proves infra/keycloak/realm-trustledger.json against a real Keycloak (FR-ID-1, FR-ID-4), and that
 * the API accepts its tokens end to end (issuer, signature, audience, role mapping).
 * <p>
 * Keycloak rejects a TOTP code that was already used ({@code otpPolicyCodeReusable=false}), so each
 * staff user logs in successfully at most once per run; tokens are cached in {@link #TOKENS}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KeycloakRealmIT {

    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    KeycloakContainer keycloak;

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Hand-built MockMvc only adds the filters it is given: the correlation filter first, then security.
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new CorrelationIdFilter())
                .apply(springSecurity()).build();
    }

    @ParameterizedTest(name = "{0} gets a token with exactly role {1}")
    @CsvSource({
            "customer1, CUSTOMER",
            "customer2, CUSTOMER",
            "officer1,  COMPLIANCE_OFFICER",
            "officer2,  COMPLIANCE_OFFICER",
            "admin1,    ADMIN",
            "auditor1,  AUDITOR",
    })
    void everyTestUserGetsATokenWithOnlyTheirApplicationRole(String username, String role) {
        JsonNode claims = KeycloakTokens.claims(accessToken(username));

        List<String> appRoles = new ArrayList<>();
        claims.path("realm_access").path("roles").forEach(r -> {
            if (List.of("CUSTOMER", "COMPLIANCE_OFFICER", "ADMIN", "AUDITOR").contains(r.asString())) {
                appRoles.add(r.asString());
            }
        });
        // Staff are never customers too (separation of duties).
        assertThat(appRoles).containsExactly(role);
        assertThat(claims.path("preferred_username").asString()).isEqualTo(username);
        assertThat(claims.path("iss").asString()).isEqualTo(keycloak.issuerUri());
    }

    @ParameterizedTest
    @ValueSource(strings = {"officer1", "officer2", "admin1"})
    void staffCannotLogInWithPasswordOnly(String username) {
        // Same password that succeeds together with a TOTP code (see accessToken()), so OTP is what's missing.
        TokenResponse response = KeycloakTokens.passwordGrant(keycloak, DEV_CLIENT, username, null);

        assertThat(response.status()).as(response.body().toString()).isEqualTo(400);
        assertThat(response.error()).isEqualTo("invalid_grant");
        assertThat(response.accessToken()).isEmpty();
    }

    @Test
    void staffCannotLogInWithAWrongOtp() {
        String wrong = KeycloakTokens.totp("some-other-secret", Instant.now());

        TokenResponse response = KeycloakTokens.passwordGrant(keycloak, DEV_CLIENT, "officer2", wrong);

        assertThat(response.status()).as(response.body().toString()).isEqualTo(400);
        assertThat(response.error()).isEqualTo("invalid_grant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer1", "auditor1"})
    void nonStaffNeedNoOtp(String username) {
        assertThat(accessToken(username)).isNotBlank();
    }

    @Test
    void accessTokenLivesFiveMinutesAndTargetsTheApi() {
        JsonNode claims = KeycloakTokens.claims(accessToken("customer1"));

        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(300);
        assertThat(claims.path("aud").toString()).contains("trustledger-api");
        assertThat(claims.path("azp").asString()).isEqualTo(DEV_CLIENT);
    }

    @Test
    void refreshTokensRotateAndCannotBeReused() {
        TokenResponse login = KeycloakTokens.passwordGrant(keycloak, DEV_CLIENT, "customer2", null);
        assertThat(login.status()).isEqualTo(200);

        TokenResponse first = KeycloakTokens.refresh(keycloak, login.refreshToken());
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.refreshToken()).isNotEqualTo(login.refreshToken());

        TokenResponse reused = KeycloakTokens.refresh(keycloak, login.refreshToken());
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.error()).isEqualTo("invalid_grant");
    }

    @Test
    void webClientDoesNotAllowThePasswordGrant() {
        TokenResponse response = KeycloakTokens.passwordGrant(keycloak, WEB_CLIENT, "customer1", null);

        assertThat(response.status()).isIn(400, 401);
        assertThat(response.error()).isEqualTo("unauthorized_client");
    }

    @Test
    void webClientRequiresPkce() {
        String authorize = keycloak.issuerUri() + "/protocol/openid-connect/auth?client_id=" + WEB_CLIENT
                + "&response_type=code&scope=openid&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fcallback";

        HttpResponse<String> withoutPkce = KeycloakTokens.get(authorize);
        assertThat(withoutPkce.statusCode()).isEqualTo(302);
        assertThat(withoutPkce.headers().firstValue("Location")).get().asString()
                .startsWith("http://localhost:5173/callback")
                .contains("error=invalid_request")
                .contains("code_challenge");

        HttpResponse<String> withPkce = KeycloakTokens.get(authorize
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256");
        assertThat(withPkce.statusCode()).isEqualTo(200); // the login page
    }

    @ParameterizedTest(name = "{0} calls /api/v1/ping with a real token")
    @CsvSource({
            "customer1, CUSTOMER",
            "officer1,  COMPLIANCE_OFFICER",
            "admin1,    ADMIN",
            "auditor1,  AUDITOR",
    })
    void apiAcceptsRealKeycloakTokens(String username, String role) throws Exception {
        String token = accessToken(username);
        String subject = KeycloakTokens.claims(token).path("sub").asString();

        mvc.perform(get("/api/v1/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.roles", contains(role)));
    }

    @Test
    void realTokenStillNeedsTheRightRole() throws Exception {
        mvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("customer1")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken("admin1")))
                .andExpect(status().isOk());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String[] parts = accessToken("customer1").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]))
                .replace("\"CUSTOMER\"", "\"ADMIN\"");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
                + "." + parts[2];

        mvc.perform(get("/api/v1/admin/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedTokenIsRejected() throws Exception {
        String[] parts = accessToken("customer1").split("\\.");
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());

        mvc.perform(get("/api/v1/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + none + "." + parts[1] + "."))
                .andExpect(status().isUnauthorized());
    }

    /** One successful login per user and run (OTP codes are single-use). */
    private String accessToken(String username) {
        return TOKENS.computeIfAbsent(username, u -> {
            String otp = u.startsWith("officer") || u.startsWith("admin")
                    ? KeycloakTokens.totp(KeycloakContainer.TEST_TOTP_SECRET, Instant.now())
                    : null;
            TokenResponse response = KeycloakTokens.passwordGrant(keycloak, DEV_CLIENT, u, otp);
            assertThat(response.status()).as("login of %s: %s", u, response.body()).isEqualTo(200);
            return response.accessToken();
        });
    }
}
