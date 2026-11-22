package com.trustledger.aml.shared;

/** Paise as a decimal rupee string (SPEC §6 "Money": amounts leave as decimal strings). No floating point. */
public final class Amounts {

    private Amounts() {
    }

    /** {@code 4600000 -> "46000.00"}, {@code -5 -> "-0.05"}. */
    public static String decimal(long amountMinor) {
        String sign = amountMinor < 0 ? "-" : "";
        long abs = Math.abs(amountMinor);
        return "%s%d.%02d".formatted(sign, abs / 100, abs % 100);
    }
}
