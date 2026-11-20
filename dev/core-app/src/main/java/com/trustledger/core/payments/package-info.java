/**
 * Payments module (FR-P-1 to FR-P-6): P2P transfers (M6) and top-ups (M7).
 * <p>
 * The transfer flow (SPEC §4 "Transfer flow"): idempotency keys ({@code @Idempotent} endpoints,
 * {@code payments.idempotency_keys}), the {@code Transfer} aggregate, recipient resolution, daily and monthly
 * limit checks, and {@code TransferService}, which locks both wallets, posts to the ledger and publishes
 * {@link com.trustledger.core.payments.TransferCompleted}. High-value transfers are held and decided by a compliance
 * officer through {@code HeldTransferService} (approve posts and publishes; reject releases the hold).
 * <p>
 * Top-ups (M7) reach Razorpay (test mode) through the {@code PaymentGateway} port; {@code RazorpayGatewayAdapter}
 * implements it with {@code RestClient} behind a Resilience4j retry (GETs only), circuit breaker and time limiter
 * (ADR 0014). {@code TopupService} starts a top-up: the {@code Topup} aggregate is saved CREATED under the wallet
 * lock (monthly load and balance cap, {@code TopupLimitChecker}), the order is created with no transaction open,
 * then the top-up is PENDING (or FAILED if the gateway failed). The wallet is credited by the webhook (part 3).
 * <p>
 * Webhooks (FR-P-5): {@code POST /webhooks/razorpay} is public; {@code WebhookService} verifies the HMAC over the raw
 * body, stores every delivery in {@code payments.webhook_events} (accepted or rejected, personal fields removed),
 * de-duplicates accepted events by gateway event ID, and hands new ones to {@code WebhookProcessor}, which runs the
 * {@code WebhookEventHandler} for the type in its own transaction. Types without a handler are stored and ignored.
 * <p>
 * Payments depends on identity, wallet and ledger through their public APIs only
 * ({@link com.trustledger.core.identity.IdentityApi}, {@link com.trustledger.core.wallet.WalletApi},
 * {@link com.trustledger.core.ledger.LedgerApi}). Its public API is the {@code TransferCompleted} event;
 * everything in sub-packages is internal.
 */
package com.trustledger.core.payments;
