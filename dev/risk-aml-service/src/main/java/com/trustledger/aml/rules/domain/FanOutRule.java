package com.trustledger.aml.rules.domain;

import com.trustledger.aml.facts.domain.TransactionFact;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** R4 Fan-out (HIGH): transfers to 10 or more distinct recipients within 24 hours. */
@Component
public class FanOutRule implements AmlRule {

    public static final String CODE = "R4";

    private final FactWindowQueries queries;
    private final Clock clock;
    private final AmlProperties.FanOut limits;

    public FanOutRule(FactWindowQueries queries, Clock clock, AmlProperties properties) {
        this.queries = queries;
        this.clock = clock;
        this.limits = properties.fanOut();
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Severity severity() {
        return Severity.HIGH;
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
        long recipients = queries.countDistinctRecipientsSince(walletId, since);
        if (recipients < limits.minDistinctRecipients()) {
            return Optional.empty();
        }
        return Optional.of(new AlertDetails("transfers to %d distinct wallets in %s (at least %d); latest transfer %s"
                .formatted(recipients, limits.window(), limits.minDistinctRecipients(), newFact.getReferenceId())));
    }
}
