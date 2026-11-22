/**
 * Identity module (FR-ID-1 to FR-ID-3): links Keycloak users to TrustLedger user IDs (just-in-time
 * provisioning), the masked {@code /me} profile and the append-only consent history.
 * <p>
 * Public API: {@link com.trustledger.core.identity.IdentityApi},
 * {@link com.trustledger.core.identity.UserView}, {@link com.trustledger.core.identity.NotificationContact} and
 * {@link com.trustledger.core.identity.PersonalDataExport} (the user's own data for their export, FR-PR-2). Erasure
 * ({@code IdentityApi.erasePersonalData}, FR-PR-2) removes the personal fields and blind indexes and marks the user
 * ERASED; the row stays, because financial records refer to its ID.
 * Everything in sub-packages is internal.
 */
package com.trustledger.core.identity;
