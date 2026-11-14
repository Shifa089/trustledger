package com.trustledger.core.identity.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.UserView;
import com.trustledger.core.identity.domain.User;
import com.trustledger.core.identity.domain.UserRepository;
import com.trustledger.core.shared.crypto.BlindIndex;
import com.trustledger.core.shared.masking.Masking;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityApiImpl implements IdentityApi {

    private final UserRepository users;
    private final BlindIndex blindIndex;

    IdentityApiImpl(UserRepository users, BlindIndex blindIndex) {
        this.users = users;
        this.blindIndex = blindIndex;
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

    static UserView view(User user) {
        return new UserView(user.getId(), UserView.Status.valueOf(user.getStatus().name()),
                Masking.name(user.getFullName()), Masking.phone(user.getPhone()));
    }
}
