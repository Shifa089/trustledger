package com.trustledger.core.identity.application;

import com.trustledger.core.identity.domain.Consent;
import com.trustledger.core.identity.domain.ConsentChoice;
import com.trustledger.core.identity.domain.ConsentPolicy;
import com.trustledger.core.identity.domain.ConsentPurpose;
import com.trustledger.core.identity.domain.ConsentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reads consent (FR-ID-3). Recording only ever inserts: the history is the evidence of
 * what the user agreed to, when and from where.
 */
@Service
public class ConsentService {

    private final ConsentRepository consents;
    private final ConsentPolicy policy;
    private final Clock clock;

    public ConsentService(ConsentRepository consents, IdentityProperties properties, Clock clock) {
        this.consents = consents;
        this.policy = new ConsentPolicy(properties.policyVersions());
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConsentOverview current(UUID userId) {
        return overview(consents.findLatestPerPurpose(userId));
    }

    /**
     * Validates all choices first (all or nothing), then appends one row per choice with the same
     * timestamp and IP.
     *
     * @param ip the client address as seen by the server; may be {@code null}
     */
    @Transactional
    public ConsentOverview record(UUID userId, List<ConsentChoice> choices, String ip) {
        Map<ConsentPurpose, Consent> latest = new EnumMap<>(ConsentPurpose.class);
        consents.findLatestPerPurpose(userId).forEach(c -> latest.put(c.getPurpose(), c));
        policy.check(latest, choices);

        // PostgreSQL timestamptz keeps microseconds: truncate so the response equals what is stored.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (ConsentChoice choice : choices) {
            Consent saved = consents.save(Consent.record(userId, choice, ip, now));
            latest.put(saved.getPurpose(), saved);
        }
        return overview(List.copyOf(latest.values()));
    }

    private ConsentOverview overview(List<Consent> latest) {
        return new ConsentOverview(policy.requiredGiven(latest), ConsentOverview.items(latest),
                policy.currentVersions());
    }
}
