package com.trustledger.aml.rules.domain;

/**
 * Alert severity (SPEC §2 rules table), lowest first (the case list shows the highest). A HIGH alert requests a
 * wallet freeze (FR-AML-3).
 */
public enum Severity {
    MEDIUM,
    HIGH
}
