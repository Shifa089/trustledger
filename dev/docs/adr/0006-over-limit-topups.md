# ADR 0006: Captured top-ups the wallet can't take are parked in PLATFORM:SUSPENSE

- Status: Accepted
- Date: 2026-11-20
- Spec: docs/SPEC.md §2 FR-P-4, FR-P-6 and the wallet limits table; §4 `payments.topups`, ledger posting rules;
  §8 M7 ("over-limit captured payments are refunded or parked in a suspense account (document your choice in an
  ADR)")

## Context

A top-up is checked against the wallet's limits when it is **created** (`TopupLimitChecker.check`: monthly load
and balance cap, open top-ups counted as if paid). The customer then pays at Razorpay, and the money arrives
later, through the `payment.captured` webhook or the `PendingTopupReconciler`. By then the check may no longer
hold:

- the wallet was **frozen** (compliance, AML) or **closed**;
- the tier went **down**, so the limits are smaller;
- money arrived by **P2P transfer** in the meantime, so the balance is higher;
- it is a **late capture**: the top-up was FAILED (the customer retried on the same order after a failed attempt)
  or EXPIRED (paid after 24 hours), and those statuses hold no room under the limits;
- the capture lands in a **new month**.

At that point Razorpay has the customer's money. It cannot be "refused": it must be either paid back or recorded
somewhere. Crediting the wallet anyway would break the RBI PPI limits the wallet tiers model (and `WalletApi.credit`
refuses a frozen or closed wallet).

Constraints:
- Money movement is one database transaction, with no remote calls while locks are held (CLAUDE.md, SPEC §3).
- The webhook is processed inside `WebhookProcessor`'s transaction; if it throws, Razorpay retries it for up to
  24 hours, and it would fail the same way every time.
- `PLATFORM:SUSPENSE` (liability: "money received that can't be attributed yet") is already seeded by V7.
- Razorpay refund creation (`POST /v1/payments/{id}/refund`) is **not idempotent** for us: a retry after a timeout
  could refund twice, the same reason order creation is never retried (ADR 0014).

## Options

1. **Refund automatically through Razorpay.** Mark the top-up REFUND_PENDING in the webhook transaction, then
   call the refund API after commit (an outbox or a retry job, since the call can't happen inside the
   transaction), handle `refund.processed` / `refund.failed` webhooks, add REFUND_PENDING / REFUNDED statuses, a
   refund ID column and a `refundPayment` port method, and guard against double refunds on retry.
   - For: the customer gets the money back without anyone stepping in.
   - Against: a new outbound money call with its own failure modes (timeouts, partial refunds, refund failures),
     about one more session of work, and refunds that can only be truly tested against Razorpay itself.
2. **Park the money in `PLATFORM:SUSPENSE`.** In the same transaction that would have credited the wallet, post
   Dr `PLATFORM:GATEWAY_CLEARING` / Cr `PLATFORM:SUSPENSE` instead, and mark the top-up **PARKED** (with the
   payment ID and the journal entry). No wallet credit, no `TopupCaptured` event. An operator settles it later.
   - For: no remote call, no new failure mode, one atomic transaction; the ledger stays balanced and every rupee
     Razorpay holds is accounted for; uses the account that already exists for exactly this.
   - Against: manual work; the customer waits until an operator acts.
3. **Credit the wallet anyway and flag it.** Rejected: breaks the regulatory limits and can't work for a frozen
   or closed wallet.
4. **Throw and let Razorpay retry.** Rejected: the condition rarely clears within Razorpay's retry window, and in
   the end the event is dropped with the money unrecorded.

## Decision

**Option 2: park in `PLATFORM:SUSPENSE`.**

`TopupSettlementService.capture` (shared by the webhook handler and the reconciler):

1. Lock the top-up by gateway order ID (`FOR UPDATE`), then its wallet. Ignore (log) an unknown order, an already
   settled top-up, or an amount that doesn't match.
2. `TopupLimitChecker.checkCapture` under the wallet lock: the wallet is ACTIVE; balance + amount ≤ maximum
   balance; this month's CAPTURED top-ups + amount ≤ monthly load. Open top-ups are not counted (they reserved
   their own room when created; the one being captured is itself still open). Landing exactly on a limit is
   allowed.
3. Fits → Dr GATEWAY_CLEARING / Cr `WALLET:<id>`, credit the wallet, CAPTURED, publish `TopupCaptured`.
4. Doesn't fit → Dr GATEWAY_CLEARING / Cr SUSPENSE, PARKED, WARN log with IDs and the reason
   (`WALLET_NOT_ACTIVE`, `MAX_BALANCE`, `MONTHLY_LOAD`), never personal data.

Both postings are a `TOPUP` entry referenced as (`TOPUP`, top-up ID), so a top-up can be booked at most once, to
the wallet or to suspense, whichever path gets there first.

V15 adds PARKED to the status check and requires a payment ID and journal entry exactly for CAPTURED and PARKED.

## Consequences

- No captured payment is ever lost or credited over a limit; reconciliation (FR-L-4) is unaffected because the
  wallet's ledger account is untouched.
- **Manual resolution (until automated):** an operator finds PARKED top-ups (`status = 'PARKED'`) and either
  (a) refunds the payment from the Razorpay dashboard (test mode) and posts Dr SUSPENSE / Cr GATEWAY_CLEARING, or
  (b) once the wallet can take it (unfrozen, new month), posts Dr SUSPENSE / Cr `WALLET:<id>` and credits the
  wallet. Neither has an endpoint yet; both are follow-ups (maker-checker admin endpoint; later a refund job that
  drains PARKED rows — this ADR's option 1, built on top of parking).
- The SUSPENSE balance should be watched: a metric and alert on PARKED top-ups belong to M12 (observability).
- AML (M9) sees only credited top-ups (`TopupCaptured`). Parked money never reached the wallet; if AML needs it,
  a `TopupParked` event can be added then.
