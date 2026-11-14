package com.trustledger.core.identity.domain;

/** FR-ID-3: TERMS and PRIVACY are required to use the wallet; MARKETING is optional and withdrawable. */
public enum ConsentPurpose {

    TERMS(true),
    PRIVACY(true),
    MARKETING(false);

    private final boolean required;

    ConsentPurpose(boolean required) {
        this.required = required;
    }

    public boolean required() {
        return required;
    }
}
