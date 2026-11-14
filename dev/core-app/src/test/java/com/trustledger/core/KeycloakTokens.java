package com.trustledger.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test helper: talks to the token endpoint of a {@link KeycloakContainer} like http/auth.http does. */
public final class KeycloakTokens {

    public static final String DEV_CLIENT = "trustledger-dev";
    public static final String WEB_CLIENT = "trustledger-web";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private KeycloakTokens() {
    }

    /** Token endpoint response: HTTP status plus the parsed JSON body. */
    public record TokenResponse(int status, JsonNode body) {

        public String accessToken() {
            return body.path("access_token").asString();
        }

        public String refreshToken() {
            return body.path("refresh_token").asString();
        }

        public String error() {
            return body.path("error").asString();
        }
    }

    public static TokenResponse passwordGrant(KeycloakContainer kc, String client, String username, String otp) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", client);
        form.put("username", username);
        form.put("password", KeycloakContainer.TEST_USER_PASSWORD);
        if (otp != null) {
            form.put("totp", otp);
        }
        return post(kc.tokenEndpoint(), form);
    }

    public static TokenResponse refresh(KeycloakContainer kc, String refreshToken) {
        return post(kc.tokenEndpoint(), Map.of("grant_type", "refresh_token", "client_id", DEV_CLIENT,
                "refresh_token", refreshToken));
    }

    /** GET without following redirects. */
    public static HttpResponse<String> get(String url) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Decodes (does not verify) the JWT payload. */
    public static JsonNode claims(String jwt) {
        String payload = jwt.split("\\.")[1];
        return JSON.readTree(Base64.getUrlDecoder().decode(payload));
    }

    /** RFC 6238 TOTP (HmacSHA1, 6 digits, 30 s) with Keycloak's raw secret, i.e. its UTF-8 bytes as the key. */
    public static String totp(String secret, Instant now) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(now.getEpochSecond() / 30).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TokenResponse post(String url, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            return new TokenResponse(response.statusCode(), JSON.readTree(response.body()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
