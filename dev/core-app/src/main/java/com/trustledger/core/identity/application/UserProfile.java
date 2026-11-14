package com.trustledger.core.identity.application;

import com.trustledger.core.identity.domain.User;
import com.trustledger.core.identity.domain.UserStatus;
import com.trustledger.core.shared.masking.Masking;
import java.util.UUID;

/**
 * A user's own profile, already masked (FR-PR-1): plaintext personal data never leaves the
 * application layer, so a controller cannot return it by mistake.
 */
public record UserProfile(UUID id, String email, String phone, String fullName, UserStatus status) {

    static UserProfile of(User user) {
        return new UserProfile(user.getId(), Masking.email(user.getEmail()), Masking.phone(user.getPhone()),
                Masking.name(user.getFullName()), user.getStatus());
    }
}
