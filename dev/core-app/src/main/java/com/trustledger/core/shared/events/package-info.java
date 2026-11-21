/**
 * The outbox's way out (M8, ADR 0005 part B): which events go to Kafka and how ({@link
 * com.trustledger.core.shared.events.EventExternalizationConfig}), the contract topics for local and test, and the
 * cleanup of completed publications. Knows no business module: events opt in with {@code @Externalized} and
 * {@link com.trustledger.core.shared.ExternalizedEvent}.
 */
package com.trustledger.core.shared.events;
