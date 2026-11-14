package com.trustledger.core.identity.domain;

import com.trustledger.core.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * A TrustLedger user, created just-in-time from the Keycloak token (FR-ID-2). Keycloak owns
 * credentials and roles; this row links the token subject to our own user ID and holds the profile.
 * <p>
 * Personal data (e-mail, phone, full name) is encrypted at rest by {@link EncryptedStringConverter}
 * (FR-PR-1); the getters return plaintext, so callers must mask before returning or logging it.
 * Lookups go through the blind-index columns ({@code emailHash}, {@code phoneHash}).
 */
@Entity
@Table(name = "users", schema = "identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    static final int MAX_SUBJECT_LENGTH = 255;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "keycloak_subject", nullable = false, updatable = false, length = MAX_SUBJECT_LENGTH)
    private String keycloakSubject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_enc", columnDefinition = "text")
    private String email;

    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "phone_enc", columnDefinition = "text")
    private String phone;

    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "full_name_enc", columnDefinition = "text")
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * New ACTIVE user from token claims. E-mail and name are optional (a token may not carry them);
     * an e-mail must come with its blind index.
     *
     * @param email     plaintext e-mail or {@code null}
     * @param emailHash {@code BlindIndex.email(email)} or {@code null} when there is no e-mail
     */
    public static User provision(String keycloakSubject, String email, String emailHash, String fullName,
            Instant now) {
        if (keycloakSubject == null || keycloakSubject.isBlank() || keycloakSubject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("keycloakSubject must be 1-" + MAX_SUBJECT_LENGTH + " characters");
        }
        requirePair("email", email, emailHash);
        User user = new User();
        user.keycloakSubject = keycloakSubject;
        user.email = blankToNull(email);
        user.emailHash = user.email == null ? null : emailHash;
        user.fullName = blankToNull(fullName);
        user.status = UserStatus.ACTIVE;
        user.createdAt = Objects.requireNonNull(now, "now");
        user.updatedAt = now;
        return user;
    }

    /**
     * Sets the verified mobile number (from minimum KYC, M3). Expects the canonical 10-digit form
     * ({@code BlindIndex.normalisePhone}) and its blind index. The database keeps one account per number.
     */
    public void changePhone(String phone, String phoneHash, Instant now) {
        requireActive();
        if (phone == null || !phone.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("phone must be the normalised 10-digit form");
        }
        requirePair("phone", phone, phoneHash);
        this.phone = phone;
        this.phoneHash = phoneHash;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("User is " + status);
        }
    }

    private static void requirePair(String field, String value, String hash) {
        boolean hasValue = value != null && !value.isBlank();
        boolean hasHash = hash != null && !hash.isBlank();
        if (hasValue != hasHash) {
            throw new IllegalArgumentException(field + " and its blind index must be set together");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
