/**
 * Privacy module (FR-PR-2, M10): a customer's data export and erasure, recorded in {@code privacy.data_requests}.
 * <p>
 * <b>Export</b> ({@code POST /me/data-export}) is asynchronous: the request is stored PENDING and an internal
 * {@code DataExportRequested} event is published in the same transaction. After the commit a listener collects the
 * user's data through the other modules' public APIs only ({@link com.trustledger.core.identity.IdentityApi},
 * {@link com.trustledger.core.kyc.KycApi}, {@link com.trustledger.core.wallet.WalletApi},
 * {@link com.trustledger.core.payments.PaymentsApi}), writes one JSON file to S3 (SSE-S3) behind the
 * {@code ExportStorage} port and marks the request COMPLETED (or FAILED). {@code GET /me/data-requests} lists the
 * caller's requests, with a fresh presigned download link (10 minutes) for each completed export.
 * <p>
 * <b>Erasure</b> ({@code POST /me/erasure-request}) is synchronous and runs in one transaction
 * ({@code ErasureService}): lock the wallet, refuse while an AML case is open or money is on or heading to the wallet,
 * close the wallet, erase the personal fields and blind indexes in identity, record a COMPLETED ERASURE request and
 * publish {@link com.trustledger.core.privacy.PersonalDataErased} (audited before the commit). Ledger, transfers,
 * top-ups, audit events and KYC records are not touched (ADR 0009). Open AML cases come from core-app's own copy,
 * {@code privacy.aml_cases}, fed by {@code AmlCaseStatusChangedV1} from risk-aml-service (ADR 0007).
 * <p>
 * Public API: the {@link com.trustledger.core.privacy.PersonalDataErased} event (the audit module listens to it).
 */
package com.trustledger.core.privacy;
