package com.trustledger.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V2 migration: encryption at rest, unique constraints and the append-only consent table, in real PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityMigrationIT {

    private static final String EMAIL = "migration.check@example.test";
    private static final String NAME = "Migration Check Person";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserProvisioningService provisioning;

    @Test
    void personalDataIsStoredOnlyAsCiphertext() {
        UUID id = provisioning.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), EMAIL, NAME));

        Map<String, Object> row = jdbc.queryForMap(
                "select email_enc, email_hash, full_name_enc, phone_enc, status from identity.users where id = ?", id);

        assertThat((String) row.get("email_enc")).isNotBlank().doesNotContain("migration", "example");
        assertThat((String) row.get("full_name_enc")).isNotBlank().doesNotContain("Migration", "Person");
        assertThat((String) row.get("email_hash")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(row.get("phone_enc")).isNull();
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        // No column anywhere holds the plaintext.
        Integer plaintextHits = jdbc.queryForObject(
                "select count(*) from identity.users u where u::text ilike ?", Integer.class, "%" + EMAIL + "%");
        assertThat(plaintextHits).isZero();
    }

    @Test
    void keycloakSubjectIsUnique() {
        String subject = ApiMockMvc.newSubject();
        insertUser(subject, null);

        assertThatThrownBy(() -> insertUser(subject, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_users_keycloak_subject");
    }

    @Test
    void phoneHashIsUniqueButManyUsersMayHaveNoPhone() {
        String hash = randomHash();
        insertUser(ApiMockMvc.newSubject(), hash);
        insertUser(ApiMockMvc.newSubject(), null);
        insertUser(ApiMockMvc.newSubject(), null);

        assertThatThrownBy(() -> insertUser(ApiMockMvc.newSubject(), hash))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_users_phone_hash");
    }

    @Test
    void consentsRejectUpdateAndDelete() {
        UUID userId = insertUser(ApiMockMvc.newSubject(), null);
        jdbc.update("""
                insert into identity.consents (id, user_id, purpose, policy_version, granted, captured_at, ip, created_at)
                values (?, ?, 'MARKETING', 'v1', true, ?, '127.0.0.1', ?)""",
                UUID.randomUUID(), userId, now(), now());

        assertThatThrownBy(() -> jdbc.update("update identity.consents set granted = false where user_id = ?", userId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from identity.consents where user_id = ?", userId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select granted from identity.consents where user_id = ?", Boolean.class,
                userId)).isTrue();
    }

    @Test
    void consentPurposeIsRestricted() {
        UUID userId = insertUser(ApiMockMvc.newSubject(), null);

        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.consents (id, user_id, purpose, policy_version, granted, captured_at, created_at)
                values (?, ?, 'SELL_MY_DATA', 'v1', true, ?, ?)""", UUID.randomUUID(), userId, now(), now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_consents_purpose");
    }

    @Test
    void encryptedValueWithoutItsIndexIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.users (id, keycloak_subject, phone_enc, status, created_at, updated_at, version)
                values (?, ?, 'ciphertext', 'ACTIVE', ?, ?, 0)""", UUID.randomUUID(), ApiMockMvc.newSubject(),
                now(), now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_phone_pair");
    }

    private UUID insertUser(String subject, String phoneHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into identity.users (id, keycloak_subject, phone_enc, phone_hash, status, created_at, updated_at, version)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)""",
                id, subject, phoneHash == null ? null : "ciphertext", phoneHash, now(), now());
        return id;
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static String randomHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }
}
