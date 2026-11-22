package com.trustledger.core.privacy.application;

import com.trustledger.core.privacy.domain.DataRequest;
import com.trustledger.core.privacy.domain.ExportStorage;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and stores one export (FR-PR-2): collect the data, write the JSON to S3, mark the request COMPLETED.
 * <p>
 * Not {@code @Transactional}: the data is read through the other modules' APIs (their own read transactions), the
 * upload runs with no transaction open, and the outcome is recorded in a short transaction of its own.
 * <p>
 * Idempotent: a request that is not PENDING any more is skipped, so a redelivered event (restart, ADR 0005) does
 * nothing. A failure marks the request FAILED with a reason code (never an exception message, which might quote
 * personal data) and the customer can ask again. Only when even recording the failure fails does the exception
 * escape, so the event publication stays incomplete and is retried on restart.
 */
@Service
public class DataExportProcessor {

    private static final Logger log = LoggerFactory.getLogger(DataExportProcessor.class);

    private final DataRequestWriter writer;
    private final DataExportAssembler assembler;
    private final ExportStorage storage;
    private final JsonMapper json;

    DataExportProcessor(DataRequestWriter writer, DataExportAssembler assembler, ExportStorage storage,
            JsonMapper json) {
        this.writer = writer;
        this.assembler = assembler;
        this.storage = storage;
        this.json = json;
    }

    public void process(UUID requestId) {
        Optional<DataRequest> found = writer.find(requestId);
        if (found.isEmpty() || !found.get().isPending()) {
            log.info("Data export {} skipped: not pending", requestId);
            return;
        }
        DataRequest request = found.get();
        try {
            DataExportDocument document = assembler.assemble(request.getId(), request.getUserId());
            storage.putJson(request.exportKey(), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
        } catch (RuntimeException e) {
            String reason = reasonOf(e);
            log.warn("Data export {} failed: {}", requestId, reason);
            writer.fail(requestId, reason);
            return;
        }
        if (writer.completeExport(requestId)) {
            log.info("Data export {} completed", requestId);
        }
    }

    /** An error code for our own exceptions, otherwise the exception class: never the message. */
    static String reasonOf(RuntimeException e) {
        return e instanceof TrustLedgerException tle ? tle.errorCode().name() : e.getClass().getSimpleName();
    }
}
