/**
 * Identity module (FR-ID-1 to FR-ID-3): links Keycloak users to TrustLedger user IDs (just-in-time
 * provisioning), the masked {@code /me} profile and the append-only consent history.
 * <p>
 * Public API: {@link com.trustledger.core.identity.IdentityApi} and
 * {@link com.trustledger.core.identity.UserView}. Everything in sub-packages is internal.
 */
package com.trustledger.core.identity;
