package com.trustledger.core.identity.application;

import com.trustledger.core.identity.domain.UserRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the caller's own profile for {@code GET /me}. */
@Service
public class UserProfileService {

    private final UserRepository users;

    public UserProfileService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserProfile profile(UUID userId) {
        return users.findById(userId)
                .map(UserProfile::of)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "User not found."));
    }
}
