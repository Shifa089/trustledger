package com.trustledger.core.audit.application;

import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.audit.domain.AuditEntry;
import com.trustledger.core.audit.domain.AuditEvent;
import com.trustledger.core.audit.domain.AuditEventStore;
import com.trustledger.core.audit.domain.AuditHashing;
import com.trustledger.core.audit.domain.AuditMasker;
import com.trustledger.core.audit.domain.ChainedAuditEvent;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends one event to the hash chain (FR-A-1, ADR 0008), <b>in the caller's transaction</b>
 * ({@link Propagation#MANDATORY}): the audit row commits or rolls back with the business change it describes.
 * <ol>
 *   <li>{@code pg_advisory_xact_lock}: one writer at a time, until its transaction ends.</li>
 *   <li>Read the last hash. Under READ COMMITTED (the default) this statement takes a fresh snapshot <em>after</em>
 *       the lock was granted, so it sees the previous holder's committed row. Under REPEATABLE READ the snapshot
 *       could be older than the lock; the unique {@code prev_hash} constraint would then refuse the insert rather
 *       than store a fork.</li>
 *   <li>{@code hash = SHA-256(prevHash + canonical JSON)}; the first event links to the genesis hash.</li>
 *   <li>Insert.</li>
 * </ol>
 * The lock is held until commit, so audited transactions commit one at a time: the price of a linear chain.
 * Callers write as late as possible (just before commit), after all their row locks, so there is no lock-order cycle.
 */
@Service
public class AuditWriter {

    private final AuditEventStore store;
    private final Clock clock;

    public AuditWriter(AuditEventStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ChainedAuditEvent write(AuditEntry entry, AuditContext context) {
        Instant now = clock.instant();
        // Built (and validated) before the lock: a bad entry fails without making anyone wait.
        AuditEvent event = AuditEvent.of(now, AuditMasker.mask(entry), context);
        store.lockChain();
        String prevHash = store.latestHash().orElse(AuditHashing.GENESIS_HASH);
        String hash = AuditHashing.hash(prevHash, event);
        long seq = store.append(event, prevHash, hash, now);
        return new ChainedAuditEvent(seq, event, prevHash, hash);
    }
}
