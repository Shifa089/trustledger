package com.trustledger.aml.rules.domain;

import com.trustledger.aml.facts.domain.TransactionFact;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Facts, a fixed clock and the production thresholds (application.yml) for the rule unit tests. */
final class RuleFixtures {

    static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID WALLET = UUID.fromString("00000000-0000-7000-8000-000000000001");

    static final AmlProperties PROPERTIES = new AmlProperties(
            new AmlProperties.Velocity(5, Duration.ofMinutes(10)),
            new AmlProperties.Structuring(4_500_000, 4_999_999, 3, Duration.ofHours(24)),
            new AmlProperties.LargeAmount(10_000_000),
            new AmlProperties.FanOut(10, Duration.ofHours(24)));

    private RuleFixtures() {
    }

    static TransactionFact outgoing(long amountMinor) {
        return TransactionFact.outgoingTransfer(UUID.randomUUID(), WALLET, UUID.randomUUID(), UUID.randomUUID(),
                amountMinor, "INR", NOW, NOW);
    }

    static TransactionFact incoming(long amountMinor) {
        return TransactionFact.incomingTransfer(UUID.randomUUID(), WALLET, UUID.randomUUID(), UUID.randomUUID(),
                amountMinor, "INR", NOW, NOW);
    }

    static TransactionFact topup(long amountMinor) {
        return TransactionFact.topup(UUID.randomUUID(), WALLET, UUID.randomUUID(), amountMinor, "INR", NOW, NOW);
    }
}
