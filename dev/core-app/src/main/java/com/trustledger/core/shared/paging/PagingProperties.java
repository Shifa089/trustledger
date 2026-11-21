package com.trustledger.core.shared.paging;

import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code trustledger.paging.*}: page sizes of every paginated list endpoint (review queues today). Validated in the
 * constructor, so a bad value stops startup.
 *
 * @param defaultSize page size when the request has no {@code size}; between 1 and {@code maxSize}
 * @param maxSize     largest {@code size} a request may ask for; between 1 and {@link #MAX_MAX_SIZE}
 */
@ConfigurationProperties("trustledger.paging")
public record PagingProperties(int defaultSize, int maxSize) {

    /** A page is read in one query and serialised in one response; beyond this a list endpoint is the wrong tool. */
    public static final int MAX_MAX_SIZE = 1_000;

    public PagingProperties {
        if (maxSize < 1 || maxSize > MAX_MAX_SIZE) {
            throw new IllegalArgumentException("trustledger.paging.max-size must be between 1 and " + MAX_MAX_SIZE);
        }
        if (defaultSize < 1 || defaultSize > maxSize) {
            throw new IllegalArgumentException("trustledger.paging.default-size must be between 1 and max-size");
        }
    }

    /**
     * Checks a request's page and size; a missing size becomes {@link #defaultSize()}.
     *
     * @return the page size to use
     * @throws TrustLedgerException {@code VALIDATION_FAILED} for a negative page or a size outside 1..maxSize
     */
    public int checkedSize(int page, Integer size) {
        int resolved = size == null ? defaultSize : size;
        if (page < 0 || resolved < 1 || resolved > maxSize) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "page must be >= 0 and size 1-" + maxSize + ".");
        }
        return resolved;
    }
}
