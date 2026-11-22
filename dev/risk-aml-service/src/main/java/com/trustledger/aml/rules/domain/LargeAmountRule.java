package com.trustledger.aml.rules.domain;

import com.trustledger.aml.facts.domain.TransactionFact;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * R3 Large amount (MEDIUM): a single owner-initiated transaction (outgoing transfer or top-up) of ₹1,00,000 or
 * more. Needs no window and no query: the new fact alone decides.
 */
@Component
public class LargeAmountRule implements AmlRule {

    public static final String CODE = "R3";

    private final AmlProperties.LargeAmount limits;

    public LargeAmountRule(AmlProperties properties) {
        this.limits = properties.largeAmount();
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
    public Optional<AlertDetails> evaluate(UUID walletId, TransactionFact newFact) {
        if (!newFact.isInitiatedByWalletOwner() || newFact.getAmountMinor() < limits.minAmountMinor()) {
            return Optional.empty();
        }
        return Optional.of(new AlertDetails("%s %s of %s (at least %s)".formatted(newFact.getSource(),
                newFact.getReferenceId(), AlertDetails.rupees(newFact.getAmountMinor()),
                AlertDetails.rupees(limits.minAmountMinor()))));
    }
}
