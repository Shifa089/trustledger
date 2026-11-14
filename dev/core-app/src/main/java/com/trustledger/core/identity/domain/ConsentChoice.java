package com.trustledger.core.identity.domain;

import java.util.Objects;

/** What the user decided for one purpose, against the policy version they were shown. */
public record ConsentChoice(ConsentPurpose purpose, String policyVersion, boolean granted) {

    public ConsentChoice {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(policyVersion, "policyVersion");
    }
}
