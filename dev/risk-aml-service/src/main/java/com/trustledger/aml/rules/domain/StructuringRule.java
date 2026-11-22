package com.trustledger.aml.rules.domain;

import com.trustledger.aml.facts.domain.TransactionFact;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * R2 Structuring (HIGH): 3 or more transactions between ₹45,000.00 and ₹49,999.99 within 24 hours, i.e. amounts
 * kept just under the ₹50,000 high-value hold. Counts what the owner initiated: outgoing transfers and top-ups.
 * Only a new fact inside the range is checked, so an unrelated transaction doesn't re-trigger the rule.
 */
@Component
public class StructuringRule implements AmlRule {

    public static final String CODE = "R2";

    private final FactWindowQueries queries;
    private final Clock clock;
    private final AmlProperties.Structuring limits;

    public StructuringRule(FactWindowQueries queries, Clock clock, AmlProperties properties) {
        this.queries = queries;
        this.clock = clock;
        this.limits = properties.structuring();
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
        if (!newFact.isInitiatedByWalletOwner() || !inRange(newFact.getAmountMinor())) {
            return Optional.empty();
        }
        Instant since = clock.instant().minus(limits.window());
        long count = queries.countInitiatedInAmountRangeSince(walletId, limits.minAmountMinor(),
                limits.maxAmountMinor(), since);
        if (count < limits.minTransactions()) {
            return Optional.empty();
        }
        return Optional.of(new AlertDetails("%d transactions between %s and %s in %s (at least %d); latest %s %s"
                .formatted(count, AlertDetails.rupees(limits.minAmountMinor()),
                        AlertDetails.rupees(limits.maxAmountMinor()), limits.window(), limits.minTransactions(),
                        newFact.getSource(), newFact.getReferenceId())));
    }

    private boolean inRange(long amountMinor) {
        return amountMinor >= limits.minAmountMinor() && amountMinor <= limits.maxAmountMinor();
    }
}
