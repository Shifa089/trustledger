package com.trustledger.core.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void provisionCreatesActiveUserWithTimestampsFromCaller() {
        User user = User.provision("sub-1", "c1@example.test", HASH, "Test Customer One", NOW);

        assertThat(user.getKeycloakSubject()).isEqualTo("sub-1");
        assertThat(user.getEmail()).isEqualTo("c1@example.test");
        assertThat(user.getEmailHash()).isEqualTo(HASH);
        assertThat(user.getFullName()).isEqualTo("Test Customer One");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.isActive()).isTrue();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getPhone()).isNull();
        assertThat(user.getPhoneHash()).isNull();
    }

    @Test
    void emailAndNameAreOptional() {
        User user = User.provision("sub-1", null, null, "  ", NOW);

        assertThat(user.getEmail()).isNull();
        assertThat(user.getEmailHash()).isNull();
        assertThat(user.getFullName()).isNull();
    }

    @Test
    void emailWithoutItsBlindIndexIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> User.provision("sub-1", "c1@example.test", null, null, NOW))
                .withMessageContaining("blind index");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> User.provision("sub-1", null, HASH, null, NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blankSubjectIsRejected(String subject) {
        assertThatIllegalArgumentException().isThrownBy(() -> User.provision(subject, null, null, null, NOW));
    }

    @Test
    void overlongSubjectIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> User.provision("x".repeat(256), null, null, null, NOW));
    }

    @Test
    void changePhoneStoresNormalisedNumberAndIndex() {
        User user = User.provision("sub-1", null, null, null, NOW);
        Instant later = NOW.plusSeconds(60);

        user.changePhone("9876543210", HASH, later);

        assertThat(user.getPhone()).isEqualTo("9876543210");
        assertThat(user.getPhoneHash()).isEqualTo(HASH);
        assertThat(user.getUpdatedAt()).isEqualTo(later);
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+919876543210", "98765 43210", "987654321", "abcdefghij"})
    void changePhoneRequiresTheNormalisedForm(String phone) {
        User user = User.provision("sub-1", null, null, null, NOW);

        assertThatIllegalArgumentException().isThrownBy(() -> user.changePhone(phone, HASH, NOW));
    }

    @Test
    void changePhoneRequiresTheBlindIndex() {
        User user = User.provision("sub-1", null, null, null, NOW);

        assertThatIllegalArgumentException().isThrownBy(() -> user.changePhone("9876543210", null, NOW));
    }
}
