package com.trustledger.core.audit.domain;

/** The kind of thing an audit event is about, as stored in {@code audit_events.entity_type}. */
public enum AuditEntityType {

    USER,
    WALLET,
    TOPUP,
    TRANSFER
}
