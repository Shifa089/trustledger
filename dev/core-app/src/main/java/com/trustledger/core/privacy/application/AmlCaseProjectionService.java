package com.trustledger.core.privacy.application;

import com.trustledger.core.privacy.domain.AmlCaseProjection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code privacy.aml_cases} up to date from the AML service's case events (ADR 0007). Idempotent by design (see
 * {@link AmlCaseProjection}), so it needs no {@code processed_events} table: a redelivered event is simply applied
 * again and changes nothing.
 */
@Service
public class AmlCaseProjectionService {

    private static final Logger log = LoggerFactory.getLogger(AmlCaseProjectionService.class);

    private final AmlCaseProjection projection;
    private final Clock clock;

    AmlCaseProjectionService(AmlCaseProjection projection, Clock clock) {
        this.projection = projection;
        this.clock = clock;
    }

    @Transactional
    public void apply(UUID caseId, UUID walletId, boolean open, Instant changedAt) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(changedAt, "changedAt");
        projection.apply(caseId, walletId, open, changedAt, clock.instant());
        log.info("AML case status applied case={} wallet={} open={}", caseId, walletId, open);
    }
}
