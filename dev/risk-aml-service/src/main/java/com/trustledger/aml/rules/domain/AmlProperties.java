package com.trustledger.aml.rules.domain;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.aml.rules.*}: thresholds of rules R1-R4 (SPEC §2 "AML rules (configurable)"). Amounts are in
 * paise, like the facts. Severities are fixed in code: the spec doesn't make them configurable. Startup fails on
 * a missing or unsafe value.
 */
@ConfigurationProperties("trustledger.aml.rules")
public record AmlProperties(Velocity velocity, Structuring structuring, LargeAmount largeAmount, FanOut fanOut) {

    public AmlProperties {
        Objects.requireNonNull(velocity, "trustledger.aml.rules.velocity is required");
        Objects.requireNonNull(structuring, "trustledger.aml.rules.structuring is required");
        Objects.requireNonNull(largeAmount, "trustledger.aml.rules.large-amount is required");
        Objects.requireNonNull(fanOut, "trustledger.aml.rules.fan-out is required");
    }

    /**
     * R1: fires when outgoing transfers in {@code window} are <b>more than</b> {@code maxOutgoingTransfers}.
     */
    public record Velocity(int maxOutgoingTransfers, Duration window) {

        public Velocity {
            positive(maxOutgoingTransfers, "velocity.max-outgoing-transfers");
            window = checkedWindow("velocity.window", window);
        }
    }

    /**
     * R2: fires at {@code minTransactions} or more owner-initiated transactions in [{@code minAmountMinor},
     * {@code maxAmountMinor}] (both included) within {@code window}.
     */
    public record Structuring(long minAmountMinor, long maxAmountMinor, int minTransactions, Duration window) {

        public Structuring {
            positive(minAmountMinor, "structuring.min-amount-minor");
            if (maxAmountMinor < minAmountMinor) {
                throw new IllegalArgumentException(
                        "trustledger.aml.rules.structuring.max-amount-minor must be at least min-amount-minor");
            }
            positive(minTransactions, "structuring.min-transactions");
            window = checkedWindow("structuring.window", window);
        }
    }

    /** R3: fires for one owner-initiated transaction of {@code minAmountMinor} or more. */
    public record LargeAmount(long minAmountMinor) {

        public LargeAmount {
            positive(minAmountMinor, "large-amount.min-amount-minor");
        }
    }

    /** R4: fires at {@code minDistinctRecipients} or more distinct receivers within {@code window}. */
    public record FanOut(int minDistinctRecipients, Duration window) {

        public FanOut {
            positive(minDistinctRecipients, "fan-out.min-distinct-recipients");
            window = checkedWindow("fan-out.window", window);
        }
    }

    private static void positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("trustledger.aml.rules." + name + " must be positive");
        }
    }

    /** A window must be positive and at most 31 days (the fact index serves short ranges). */
    private static Duration checkedWindow(String name, Duration window) {
        Objects.requireNonNull(window, "trustledger.aml.rules." + name + " is required");
        if (window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException(
                    "trustledger.aml.rules." + name + " must be positive and at most 31 days");
        }
        return window;
    }
}
