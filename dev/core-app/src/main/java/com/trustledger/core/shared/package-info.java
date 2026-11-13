/**
 * Shared kernel: building blocks every module uses (Money, error model, correlation IDs, Clock).
 * <p>
 * Declared OPEN so other modules may use its sub-packages too; it must never depend on a business
 * module.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.trustledger.core.shared;

import org.springframework.modulith.ApplicationModule;
