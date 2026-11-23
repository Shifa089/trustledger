package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * M14: with {@code jwk-set-uri} set (the apps in containers), the keys come from that URI, and the issuer check is
 * unchanged. The issuer below is never contacted: it is an address nothing listens on.
 */
class JwkSetUriDecoderTest {

    private static final String ISSUER = "http://localhost:1/realms/trustledger";
    private static final List<String> AUDIENCES = List.of("trustledger-api");

    private final AtomicInteger jwksRequests = new AtomicInteger();
    private RSAKey key;
    private HttpServer jwks;
    private JwtDecoder decoder;

    @BeforeEach
    void startJwksEndpoint() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        jwks.createContext("/certs", exchange -> {
            jwksRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();
        String jwkSetUri = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs";
        decoder = SecurityConfig.jwtDecoder(ISSUER, jwkSetUri, AUDIENCES);
    }

    @AfterEach
    void stopJwksEndpoint() {
        jwks.stop(0);
    }

    @Test
    void acceptsATokenFromTheConfiguredIssuerSignedWithAKeyFromTheJwkSetUri() throws Exception {
        var jwt = decoder.decode(token(ISSUER, "trustledger-api"));

        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwksRequests).hasValue(1);
    }

    @Test
    void stillRejectsATokenFromAnotherIssuer() throws Exception {
        String foreign = token("http://keycloak:8080/realms/trustledger", "trustledger-api");

        assertThatThrownBy(() -> decoder.decode(foreign))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("iss");
    }

    @Test
    void stillRejectsATokenForAnotherAudience() throws Exception {
        String other = token(ISSUER, "account");

        assertThatThrownBy(() -> decoder.decode(other))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("audience");
    }

    private String token(String issuer, String audience) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject("user-1").audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
