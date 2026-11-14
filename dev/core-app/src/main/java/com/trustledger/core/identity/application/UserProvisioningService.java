package com.trustledger.core.identity.application;

import com.trustledger.core.identity.domain.User;
import com.trustledger.core.identity.domain.UserRepository;
import com.trustledger.core.shared.crypto.BlindIndex;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Just-in-time provisioning (FR-ID-2): the first authenticated request of a Keycloak user creates their
 * {@code identity.users} row; every later request finds it.
 * <p>
 * <b>Race:</b> a client often fires several requests at once right after login. Two of them can both
 * see "no row" and both insert. The unique constraint on {@code keycloak_subject} lets exactly one insert
 * win; the loser gets a {@link DataIntegrityViolationException} and re-reads the winner's row. No locks,
 * no "SELECT then INSERT" window that a check in Java could close.
 * <p>
 * The insert runs in its <em>own</em> transaction ({@code REQUIRES_NEW}): in PostgreSQL a failed statement
 * aborts the whole transaction, so the re-read must run in a fresh one, and a caller's transaction must not
 * be marked rollback-only by our expected conflict. That is why transactions are explicit
 * ({@link TransactionTemplate}) here instead of {@code @Transactional}.
 */
@Service
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository users;
    private final BlindIndex blindIndex;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate insertTx;

    public UserProvisioningService(UserRepository users, BlindIndex blindIndex, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.blindIndex = blindIndex;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.insertTx = new TransactionTemplate(transactionManager);
        this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Returns the user ID for the token subject, creating the user on first sight. Safe to call concurrently. */
    public UUID ensureUser(TokenIdentity identity) {
        Optional<UUID> existing = findId(identity.subject());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            UUID id = insertTx.execute(status -> users.saveAndFlush(newUser(identity)).getId());
            log.info("Provisioned user {} on first request", id);
            return id;
        } catch (DataIntegrityViolationException raceLost) {
            // Another request inserted the same subject between our read and our insert.
            return findId(identity.subject()).orElseThrow(() -> raceLost);
        }
    }

    private Optional<UUID> findId(String subject) {
        return readTx.execute(status -> users.findByKeycloakSubject(subject).map(User::getId));
    }

    private User newUser(TokenIdentity identity) {
        String email = blankToNull(identity.email());
        return User.provision(identity.subject(), email, email == null ? null : blindIndex.email(email),
                identity.fullName(), clock.instant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
