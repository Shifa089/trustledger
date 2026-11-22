package com.trustledger.core.privacy.domain;

/** What the customer asked for (FR-PR-2). */
public enum DataRequestType {
    /** A JSON copy of their data. */
    EXPORT,
    /** Anonymise their personal fields, keep financial records (Session B). */
    ERASURE
}
