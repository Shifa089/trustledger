package com.trustledger.core.privacy.application;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs an export after the request's transaction has committed, on another thread ({@code @Async}; Spring Modulith
 * turns on {@code @EnableAsync}). Like {@code @ApplicationModuleListener} but <b>without</b> its surrounding
 * transaction: the S3 upload must not run while a database transaction (and its connection) is open.
 * <p>
 * An AFTER_COMMIT listener, so Spring Modulith records the publication in {@code event_publication} with the
 * request and completes it when this method returns; one left incomplete by a crash is delivered again on restart
 * ({@code republish-outstanding-events-on-restart}).
 */
@Component
class DataExportListener {

    private final DataExportProcessor processor;

    DataExportListener(DataExportProcessor processor) {
        this.processor = processor;
    }

    @Async
    @TransactionalEventListener
    void on(DataExportRequested event) {
        processor.process(event.requestId());
    }
}
