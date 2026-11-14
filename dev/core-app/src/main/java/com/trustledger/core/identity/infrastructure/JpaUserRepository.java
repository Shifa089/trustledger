package com.trustledger.core.identity.infrastructure;

import com.trustledger.core.identity.domain.User;
import com.trustledger.core.identity.domain.UserRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of {@link UserRepository}: derived queries on the indexed columns. */
public interface JpaUserRepository extends JpaRepository<User, UUID>, UserRepository {
}
