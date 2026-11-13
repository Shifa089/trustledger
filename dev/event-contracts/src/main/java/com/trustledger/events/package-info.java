/**
 * Kafka event contracts shared by core-app and risk-aml-service.
 * <p>
 * Events are immutable records with a version suffix (for example {@code TransferCompletedV1}).
 * This module has no Spring dependency, so both applications can depend on it freely.
 */
package com.trustledger.events;
