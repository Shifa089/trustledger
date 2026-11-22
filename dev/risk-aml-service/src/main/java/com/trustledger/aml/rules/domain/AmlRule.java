package com.trustledger.aml.rules.domain;

import com.trustledger.aml.facts.domain.TransactionFact;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * One AML rule from SPEC §2 "AML rules" (FR-AML-1), as a Strategy: the {@code RuleEngine} runs every bean of this
 * type after each new fact. A rule only decides whether it fires; the engine turns a hit into an alert and a case.
 * <p>
 * Windows end at the injected {@code Clock}'s "now". Thresholds come from {@link AmlProperties}.
 */
public interface AmlRule {

    /** {@code R1}..{@code R4}, as stored in {@code aml.alerts.rule_code}. */
    String code();

    Severity severity();

    /**
     * @param walletId the wallet being checked: {@code newFact}'s wallet
     * @param newFact  the fact just recorded (already flushed, so the window queries count it)
     * @return what to put on the alert if the rule fires, otherwise empty
     */
    Optional<AlertDetails> evaluate(UUID walletId, TransactionFact newFact);

    /**
     * The window the rule counts over. A rule that already alerted for a wallet within it doesn't alert again,
     * so the 7th, 8th... transfer in ten minutes doesn't add one R1 alert each. Empty for a rule about a single
     * transaction (R3): every large transaction is its own alert.
     */
    default Optional<Duration> window() {
        return Optional.empty();
    }
}
