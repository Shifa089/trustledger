/**
 * Privacy module (FR-PR-2, M10): a customer's data export and, in a later session, erasure requests, recorded in
 * {@code privacy.data_requests}.
 * <p>
 * <b>Export</b> ({@code POST /me/data-export}) is asynchronous: the request is stored PENDING and an internal
 * {@code DataExportRequested} event is published in the same transaction. After the commit a listener collects the
 * user's data through the other modules' public APIs only ({@link com.trustledger.core.identity.IdentityApi},
 * {@link com.trustledger.core.kyc.KycApi}, {@link com.trustledger.core.wallet.WalletApi},
 * {@link com.trustledger.core.payments.PaymentsApi}), writes one JSON file to S3 (SSE-S3) behind the
 * {@code ExportStorage} port and marks the request COMPLETED (or FAILED). {@code GET /me/data-requests} lists the
 * caller's requests, with a fresh presigned download link (10 minutes) for each completed export.
 * <p>
 * Public API: none. Nothing depends on this module.
 */
package com.trustledger.core.privacy;
