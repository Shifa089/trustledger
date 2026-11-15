package com.trustledger.core.kyc.application;

import java.net.URI;
import java.time.Instant;

/** A short-lived presigned download link for one KYC document; anyone holding it can download until expiry. */
public record DocumentLinkView(URI url, Instant expiresAt) {

    /** The URL carries a signature: never print it in logs. */
    @Override
    public String toString() {
        return "DocumentLinkView[url=****, expiresAt=" + expiresAt + "]";
    }
}
