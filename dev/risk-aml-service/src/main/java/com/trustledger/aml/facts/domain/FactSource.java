package com.trustledger.aml.facts.domain;

/** Which kind of money movement a fact comes from; {@code reference_id} is that transfer's or top-up's ID. */
public enum FactSource {
    TRANSFER,
    TOPUP
}
