package com.trustledger.core.payments.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method that needs an {@code Idempotency-Key} header (FR-P-1; {@code POST /transfers},
 * {@code POST /topups}). {@link IdempotencyInterceptor} then:
 * <ul>
 * <li>refuses a missing or non-UUID key (400 {@code VALIDATION_FAILED});</li>
 * <li>replays the stored response of a completed request with the same key and body, without calling the
 * method;</li>
 * <li>refuses the same key with another body (422 {@code IDEMPOTENCY_KEY_REUSED}) or while the first request is
 * still running (409 {@code REQUEST_IN_PROGRESS});</li>
 * <li>stores a 2xx response, and forgets the key after any other outcome (nothing moved, so a retry may run).</li>
 * </ul>
 * Keys are per user: two customers can't collide or read each other's responses.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
