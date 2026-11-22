package com.trustledger.core.kyc;

import java.util.UUID;

/**
 * The KYC module's public API (Spring Modulith: only types in this base package are visible to other modules).
 * Other modules never see a KYC case entity, a PAN, a date of birth, an address or a document.
 */
public interface KycApi {

    /**
     * The user's KYC status for their data export (FR-PR-2): the highest approved level and every case with its
     * status and timestamps. Deliberately <b>without</b> documents, PAN, name, date of birth or address. A user
     * without any case gets level {@code NONE} and no cases.
     */
    KycStatusSummary statusOf(UUID userId);
}
