package com.trustledger.core.identity.application;

import com.trustledger.core.identity.domain.ConsentPurpose;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.identity.*}.
 *
 * @param policyVersions the currently published policy version per consent purpose (e.g. {@code 2026-11-01}).
 *                       Every purpose needs one; at most 32 characters (column size). Startup fails otherwise.
 */
@ConfigurationProperties("trustledger.identity")
public record IdentityProperties(Map<ConsentPurpose, String> policyVersions) {

    static final int MAX_VERSION_LENGTH = 32;

    public IdentityProperties {
        Map<ConsentPurpose, String> versions = new EnumMap<>(ConsentPurpose.class);
        if (policyVersions != null) {
            versions.putAll(policyVersions);
        }
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            String version = versions.get(purpose);
            if (version == null || version.isBlank() || version.length() > MAX_VERSION_LENGTH) {
                throw new IllegalArgumentException("trustledger.identity.policy-versions." + purpose
                        + " is required (1-" + MAX_VERSION_LENGTH + " characters)");
            }
        }
        policyVersions = Map.copyOf(versions);
    }
}
