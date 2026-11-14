package com.trustledger.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.identity.domain.User;
import com.trustledger.core.identity.domain.UserRepository;
import com.trustledger.core.shared.crypto.BlindIndex;
import com.trustledger.core.shared.crypto.CryptoProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The provisioning branches, made deterministic with mocks. The real race against PostgreSQL is in
 * {@code JitProvisioningIT}.
 */
class UserProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final TokenIdentity IDENTITY =
            new TokenIdentity("sub-1", "Customer.One@Example.test", "Test Customer One");

    private final UserRepository users = mock(UserRepository.class);
    private final BlindIndex blindIndex = new BlindIndex(new CryptoProperties(
            "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="));
    private final UserProvisioningService service = new UserProvisioningService(users, blindIndex,
            Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));

    @Test
    void existingUserIsReturnedWithoutInsert() {
        User existing = userWithId(UUID.randomUUID());
        when(users.findByKeycloakSubject("sub-1")).thenReturn(Optional.of(existing));

        assertThat(service.ensureUser(IDENTITY)).isEqualTo(existing.getId());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void newUserIsInsertedWithBlindIndexAndClockTime() {
        UUID id = UUID.randomUUID();
        when(users.findByKeycloakSubject("sub-1")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0), id));

        assertThat(service.ensureUser(IDENTITY)).isEqualTo(id);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getKeycloakSubject()).isEqualTo("sub-1");
        assertThat(saved.getValue().getEmailHash()).isEqualTo(blindIndex.email("customer.one@example.test"));
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void lostRaceReReadsTheWinnersRow() {
        User winner = userWithId(UUID.randomUUID());
        when(users.findByKeycloakSubject("sub-1"))
                .thenReturn(Optional.empty())       // our first read: not there yet
                .thenReturn(Optional.of(winner));   // re-read after the unique violation
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_users_keycloak_subject"));

        assertThat(service.ensureUser(IDENTITY)).isEqualTo(winner.getId());
        verify(users, times(2)).findByKeycloakSubject("sub-1");
    }

    @Test
    void integrityViolationWithoutAWinnerIsRethrown() {
        when(users.findByKeycloakSubject("sub-1")).thenReturn(Optional.empty());
        DataIntegrityViolationException violation = new DataIntegrityViolationException("other constraint");
        when(users.saveAndFlush(any())).thenThrow(violation);

        assertThatThrownBy(() -> service.ensureUser(IDENTITY)).isSameAs(violation);
    }

    @Test
    void tokenWithoutEmailProvisionsWithoutEmail() {
        when(users.findByKeycloakSubject("sub-2")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0), UUID.randomUUID()));

        service.ensureUser(new TokenIdentity("sub-2", null, null));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
        assertThat(saved.getValue().getEmailHash()).isNull();
    }

    @Test
    void tokenIdentityNeverPrintsPersonalData() {
        assertThat(IDENTITY.toString()).doesNotContain("Customer.One", "Test Customer One").contains("sub-1");
    }

    private static User userWithId(UUID id) {
        return withId(User.provision("sub-1", null, null, null, NOW), id);
    }

    /** Simulates the ID Hibernate assigns on persist. */
    private static User withId(User user, UUID id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
