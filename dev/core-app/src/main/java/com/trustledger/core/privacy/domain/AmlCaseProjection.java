package com.trustledger.core.privacy.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * core-app's own copy of which AML cases are open, per wallet ({@code privacy.aml_cases}), fed by
 * {@code AmlCaseStatusChangedV1} from risk-aml-service (ADR 0007). Erasure (FR-PR-2) reads it instead of calling the
 * AML service.
 * <p>
 * A case only ever goes from open to finished, so {@link #apply} is order-independent and idempotent: an "opened"
 * event arriving after the "finished" one, or any event arriving twice, changes nothing.
 */
public interface AmlCaseProjection {

    /**
     * Records one case event. {@code open = true} adds the case if it is unknown; {@code open = false} marks it
     * finished, adding it if it is unknown. A finished case never becomes open again.
     *
     * @param changedAt when the AML service recorded the change
     * @param now       our clock, for {@code updated_at}
     */
    void apply(UUID caseId, UUID walletId, boolean open, Instant changedAt, Instant now);

    /** Whether the wallet has an AML case that is still open (OPEN or INVESTIGATING there). */
    boolean hasOpenCase(UUID walletId);
}
