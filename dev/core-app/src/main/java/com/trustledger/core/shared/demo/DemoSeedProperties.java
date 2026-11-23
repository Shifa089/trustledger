package com.trustledger.core.shared.demo;

import com.trustledger.core.shared.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.demo.seed.*} (profile {@code demo} only, M16): the opening balances of the two demo customers.
 * Both must be positive INR amounts. They should also fit the FULL tier's maximum balance
 * ({@code trustledger.limits.full.max-balance}) with room for the AML demo's top-ups (docs/aml-demo.md).
 */
@ConfigurationProperties("trustledger.demo.seed")
public record DemoSeedProperties(Money customer1Balance, Money customer2Balance) {

    public DemoSeedProperties {
        requirePositiveInr(customer1Balance, "customer1-balance");
        requirePositiveInr(customer2Balance, "customer2-balance");
    }

    Money balanceOf(int n) {
        return n == 1 ? customer1Balance : customer2Balance;
    }

    private static void requirePositiveInr(Money balance, String name) {
        if (balance == null || balance.amountMinor() <= 0 || !balance.currency().equals(Money.INR)) {
            throw new IllegalArgumentException("trustledger.demo.seed." + name + " must be a positive INR amount");
        }
    }
}
