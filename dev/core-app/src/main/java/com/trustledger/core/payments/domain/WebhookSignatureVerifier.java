package com.trustledger.core.payments.domain;

/**
 * Port: checks a gateway webhook's signature over the <em>raw</em> request body (FR-P-5). The bytes must be exactly
 * the ones received: parsing and re-serialising the JSON changes them and breaks the signature.
 */
public interface WebhookSignatureVerifier {

    /**
     * @param rawBody   the request body exactly as received
     * @param signature the signature header; null, blank or malformed values are simply invalid
     * @return true only if the signature matches; never throws for bad input
     */
    boolean isValid(byte[] rawBody, String signature);
}
