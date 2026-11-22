package com.trustledger.aml.cases.domain;

import java.util.Set;

/** FR-AML-4: OPEN → INVESTIGATING → CLOSED_FALSE_POSITIVE or ESCALATED (STR). */
public enum CaseStatus {
    OPEN,
    INVESTIGATING,
    CLOSED_FALSE_POSITIVE,
    ESCALATED;

    /** Still being worked: new alerts for the wallet join this case (at most one per wallet, SPEC §4). */
    public static final Set<CaseStatus> ACTIVE = Set.of(OPEN, INVESTIGATING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
