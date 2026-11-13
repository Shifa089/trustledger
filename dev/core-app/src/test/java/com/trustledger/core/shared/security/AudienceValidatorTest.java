package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private final OAuth2TokenValidator<Jwt> validator = SecurityConfig.audienceValidator(List.of("trustledger-api"));

    @Test
    void acceptsTokenForTheApi() {
        assertThat(validator.validate(jwt(List.of("account", "trustledger-api"))).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenForAnotherAudience() {
        var result = validator.validate(jwt(List.of("account")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).first().extracting(e -> e.getErrorCode()).isEqualTo("invalid_token");
    }

    @Test
    void rejectsTokenWithoutAudience() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    private static Jwt jwt(List<String> audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u").audience(audience)
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
    }
}
