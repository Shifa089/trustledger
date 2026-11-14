package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.trustledger.core.shared.security.SecurityProperties.Cors;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityPropertiesTest {

    @Test
    void defaultsAreClosed() {
        SecurityProperties properties = new SecurityProperties(null, false);

        assertThat(properties.cors().allowedOrigins()).isEmpty();
        assertThat(properties.swaggerPublic()).isFalse();
        assertThat(new Cors(null).allowedOrigins()).isEmpty();
    }

    @Test
    void acceptsExactOrigins() {
        Cors cors = new Cors(List.of("http://localhost:5173", "https://app.trustledger.dev"));

        assertThat(cors.allowedOrigins()).containsExactly("http://localhost:5173", "https://app.trustledger.dev");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.trustledger.dev", "", " ", "localhost:5173", "ftp://example.test",
            "https://app.trustledger.dev/", "https://app.trustledger.dev/path", "https://user@app.trustledger.dev",
            "https://app.trustledger.dev?x=1", "not a url"})
    void rejectsWildcardsAndNonOrigins(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cors(List.of(origin)));
    }

    @Test
    void originListIsImmutable() {
        Cors cors = new Cors(new java.util.ArrayList<>(List.of("http://localhost:5173")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cors.allowedOrigins().add("http://evil.test"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
