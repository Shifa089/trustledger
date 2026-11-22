package com.trustledger.core.privacy.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.privacy.*} (FR-PR-2). Startup fails on an unsafe value instead of on the first request.
 *
 * @param exportLinkTtl     lifetime of the presigned download link of a completed export; 1 second to 10 minutes
 *                          (the export holds personal data in clear, so a leaked link must die quickly)
 * @param maxListedRequests most requests {@code GET /me/data-requests} returns, newest first; 1-200
 */
@ConfigurationProperties("trustledger.privacy")
public record PrivacyProperties(Duration exportLinkTtl, int maxListedRequests) {

    static final Duration MAX_EXPORT_LINK_TTL = Duration.ofMinutes(10);
    static final int MAX_LISTED_LIMIT = 200;

    public PrivacyProperties {
        if (exportLinkTtl == null || exportLinkTtl.compareTo(Duration.ofSeconds(1)) < 0
                || exportLinkTtl.compareTo(MAX_EXPORT_LINK_TTL) > 0) {
            throw new IllegalArgumentException(
                    "trustledger.privacy.export-link-ttl must be 1s-10m, was " + exportLinkTtl);
        }
        if (maxListedRequests < 1 || maxListedRequests > MAX_LISTED_LIMIT) {
            throw new IllegalArgumentException("trustledger.privacy.max-listed-requests must be 1-" + MAX_LISTED_LIMIT
                    + ", was " + maxListedRequests);
        }
    }
}
