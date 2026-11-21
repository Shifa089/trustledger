package com.trustledger.core.identity.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.NotificationContact;
import com.trustledger.core.identity.UserView;
import com.trustledger.core.identity.domain.User;
import com.trustledger.core.identity.domain.UserRepository;
import com.trustledger.core.identity.domain.UserStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.crypto.BlindIndex;
import com.trustledger.core.shared.masking.Masking;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityApiImpl implements IdentityApi {

    private final UserRepository users;
    private final BlindIndex blindIndex;
    private final Clock clock;

    IdentityApiImpl(UserRepository users, BlindIndex blindIndex, Clock clock) {
        this.users = users;
        this.blindIndex = blindIndex;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findUserId(String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()) {
            return Optional.empty();
        }
        return users.findByKeycloakSubject(keycloakSubject).map(User::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findByPhone(String phone) {
        String hash;
        try {
            hash = blindIndex.phone(phone);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        return users.findByPhoneHash(hash).map(IdentityApiImpl::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserView> findUser(UUID userId) {
        return userId == null ? Optional.empty() : users.findById(userId).map(IdentityApiImpl::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationContact> findNotificationContact(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return users.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
                .map(user -> new NotificationContact(user.getId(), user.getEmail()));
    }

    @Override
    @Transactional
    public void recordVerifiedPhone(UUID userId, String phone) {
        String normalised;
        try {
            normalised = BlindIndex.normalisePhone(phone);
        } catch (IllegalArgumentException malformed) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "The mobile number is not valid.");
        }
        String hash = blindIndex.phone(normalised);
        User user = users.findById(userId)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "User not found."));
        Optional<User> owner = users.findByPhoneHash(hash);
        if (owner.isPresent()) {
            if (owner.get().getId().equals(userId)) {
                return; // already recorded
            }
            throw phoneTaken();
        }
        user.changePhone(normalised, hash, clock.instant());
        try {
            // Flush now: a concurrent registration of the same number hits uk_users_phone_hash here.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException raceLost) {
            throw phoneTaken();
        }
    }

    private static TrustLedgerException phoneTaken() {
        return new TrustLedgerException(ErrorCode.PHONE_ALREADY_REGISTERED,
                "This mobile number is already registered to another account.");
    }

    static UserView view(User user) {
        return new UserView(user.getId(), UserView.Status.valueOf(user.getStatus().name()),
                Masking.name(user.getFullName()), Masking.phone(user.getPhone()));
    }
}
