package com.trustledger.aml.rules.domain;

import com.trustledger.aml.facts.domain.TransactionFact;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** R1 Velocity (MEDIUM): more than 5 outgoing transfers within 10 minutes. */
@Component
public class VelocityRule implements AmlRule {

    public static final String CODE = "R1";

    private final FactWindowQueries queries;
    private final Clock clock;
    private final AmlProperties.Velocity limits;

    public VelocityRule(FactWindowQueries queries, Clock clock, AmlProperties properties) {
        this.queries = queries;
        this.clock = clock;
        this.limits = properties.velocity();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Severity severity() {
        return Severity.MEDIUM;
    }

    @Override
    public Optional<Duration> window() {
        return Optional.of(limits.window());
    }

    @Override
    public Optional<AlertDetails> evaluate(UUID walletId, TransactionFact newFact) {
        if (!newFact.isOutgoingTransfer()) {
            return Optional.empty();
        }
        Instant since = clock.instant().minus(limits.window());
        long count = queries.countOutgoingTransfersSince(walletId, since);
        if (count <= limits.maxOutgoingTransfers()) {
            return Optional.empty();
        }
        return Optional.of(new AlertDetails("%d outgoing transfers in %s (more than %d); latest transfer %s"
                .formatted(count, limits.window(), limits.maxOutgoingTransfers(), newFact.getReferenceId())));
    }
}
