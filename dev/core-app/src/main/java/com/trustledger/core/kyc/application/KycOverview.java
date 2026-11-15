package com.trustledger.core.kyc.application;

import java.util.List;

/**
 * {@code GET /kyc/me}: the highest approved level ({@code NONE}, {@code MIN} or {@code FULL}) and every case
 * of the caller, oldest first.
 */
public record KycOverview(String currentLevel, List<KycCaseView> cases) {

    public static final String NONE = "NONE";
}
