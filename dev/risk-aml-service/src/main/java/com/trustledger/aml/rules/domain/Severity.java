package com.trustledger.aml.rules.domain;

/** Alert severity (SPEC §2 rules table). A HIGH alert will request a wallet freeze (FR-AML-3). */
public enum Severity {
    MEDIUM,
    HIGH
}
