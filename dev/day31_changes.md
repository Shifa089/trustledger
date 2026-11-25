# Changes

## DAY31 (F3): add money, send money, privacy

**Goal:** the money flows work from the browser, safely (build plan "Frontend and live demo", Day 31). Requirements:
SPEC FR-P-1..6, FR-PR-1/2, FR-ID-3, and the `/topups`, `/transfers` and `/me/*` endpoints and error codes in §5.

Branch: `feature/day31-frontend-f3` (from `master` after the Day 29-30 merge). Frontend only: no backend code changed.

### Decisions

- **The screen owns the Idempotency-Key, one per attempt.**
  - The backend replays a stored 2xx for a key it has seen, and releases the key on any non-2xx
    (`IdempotencyInterceptor.afterCompletion`). So reusing a key on retry is always safe, even after a 422.
  - **Send money:** pressing "Review" fixes the request body and makes one key. "Send" and "Try again" always send
    that key. "Back to edit" throws it away, so changed details are a new attempt and can never collide with the
    old key (`IDEMPOTENCY_KEY_REUSED`).
  - **Add money:** one key per form submission. Submitting the same amount again after a failed create reuses it.
    A new amount, or a top-up that was created, gets a new key.
- **Double clicks are stopped twice.**
  - A `useRef` in-flight flag is checked synchronously. The second click of a double click arrives before React has
    re-rendered the button as disabled, so state alone would let it through.
  - The button is also `disabled` with "Sending…" while the request runs.
  - If both failed, the server would still replay rather than pay twice.
- **Uncertain failures say so.** On a network error, a 5xx or `REQUEST_IN_PROGRESS`, the alert says "Not confirmed
  yet" and that trying again is safe. On a business refusal it says "Transfer not sent".
  - The five codes from the brief (INSUFFICIENT_FUNDS, LIMIT_EXCEEDED, WALLET_FROZEN, KYC_REQUIRED, RATE_LIMITED) use
    the existing friendly messages, plus a "Go to KYC" link for KYC_REQUIRED.
  - An unknown recipient (404) gets its own wording instead of the generic not-found text.
- **HELD_FOR_REVIEW has its own result card,** "On hold for review". It says the money has left the available
  balance but isn't lost, shows as "held" on the dashboard, and comes back if the transfer is rejected.
- **Recipient by mobile or wallet ID.**
  - The seeded customers have no mobile number, so the Send money page also shows **your own wallet ID** with a copy
    button. That is the only way to receive money from them, and the e2e test reads it.
  - Sending to your own wallet ID is refused in the form too (the backend refuses it as well).
- **The Zod rules mirror the backend.**
  - Amount: `Money.parse` (`\d+(\.\d{1,2})?`, > 0), normalised to two decimals with bigint paise.
  - Mobile: `@ValidIndianMobile`. Wallet ID: a UUID. Note: ≤ 140 characters (`Transfer.MAX_NOTE_LENGTH`).
  - Zod 4 runs every refinement even after the pattern fails, so the bigint checks guard themselves (found by the
    first test run: `parseRupees("")` threw).
- **Razorpay Checkout is a hosted script, not an npm dependency.**
  - `features/topup/razorpay.ts` loads `checkout.razorpay.com/v1/checkout.js` once, on first use. The backend's M7
    `checkout.html` already used it.
  - Only the public test key ID and the order ID from `POST /topups` reach the browser.
  - The component takes the opener as a prop, so tests use a fake Checkout.
  - Recorded in ADR 0023, including the CSP entries Day 33 will need.
- **Top-up polling.**
  - Polling starts only after Checkout reports a payment: every 2 s, at most 30 times (about a minute), with
    `GET /topups/{id}`. It stops on CAPTURED, PARKED, FAILED or EXPIRED.
  - A failed read is not a failed payment, so polling keeps going.
  - When the window ends, the page says "We'll confirm it shortly": the money is with Razorpay, don't pay again, and
    the webhook or the reconciler (FR-P-6) will credit it. A "Check again" button polls once more.
  - Closing Checkout shows "No payment was made" and doesn't poll.
  - PARKED and FAILED explain that captured money is never lost.
- **The add-money hint** ("You can add up to ₹X now") is the smaller of the room under the balance cap and the rest
  of this month's load limit, in bigint. It is only a hint: the server's check, which also counts open top-ups,
  decides.
- **Privacy page.**
  - **Marketing consent:** a labelled `role="switch"` checkbox. It posts the *current* policy version from
    `policyVersions.MARKETING`. TERMS and PRIVACY are shown read-only.
  - **Data export:** "Request data export" (202), then `/me/data-requests` is re-read every 3 s while any request is
    PENDING. A COMPLETED export shows "Download your data (JSON)" (`target=_blank`, `rel="noopener noreferrer"`) and
    when the link expires; "Refresh links" gets fresh presigned URLs.
  - Only `http:`/`https:` download URLs are rendered, never `javascript:` or `data:`.
  - **Erasure:** "Erase my data" stays disabled until exactly `ERASE` is typed (no trimming, case-sensitive). Enter
    can't bypass it. The card says what is anonymised and what is kept (ledger, transfers, audit), and that the
    wallet must be empty, not frozen, and free of open AML cases.
  - After erasure the page shows only "Your personal data has been erased" with a sign-out button.

### Files

**Frontend** (`frontend/`)
- `src/api/`:
  - `types.ts`: Transfer, Topup, consent and data-request types.
  - `payments.ts` (new): `createTransfer`, `createTopup`, `fetchTopup`, `newIdempotencyKey`, mutation hooks; the wallet
    queries are refreshed after a transfer.
  - `privacy.ts` (new): consents, data requests (with polling), export and erasure hooks.
- `src/features/transfer/` (new): `schemas.ts`, `SendMoneyForm.tsx`, `SendMoneyPage.tsx`.
- `src/features/topup/` (new): `razorpay.ts`, `schemas.ts`, `room.ts`, `TopupFlow.tsx`, `AddMoneyPage.tsx`.
- `src/features/privacy/PrivacyPage.tsx` (new).
- `src/router.tsx`: `/customer/add-money`, `/customer/send` and `/customer/privacy` under `RequireRole CUSTOMER`.
  `src/nav.ts`: the three items are links now (no longer "soon").
- `src/test/server.ts`: default handlers for `/me/consents` and `/me/data-requests`.

**Tests**
- `SendMoneyForm.test.tsx` (22):
  - A double click sends one request (the server is held open, the button shows "Sending…").
  - A retry after a network error reuses the same key and body.
  - Going back and changing the amount gets a new key.
  - Each of INSUFFICIENT_FUNDS, LIMIT_EXCEEDED, WALLET_FROZEN, KYC_REQUIRED and RATE_LIMITED shows its message and
    the reference.
  - Unknown recipient, HELD_FOR_REVIEW, the exact body by mobile and by wallet ID, and invalid mobile, amounts and
    wallet IDs (including your own) each send nothing.
  - The page: wallet ID shown, MIN tier asks for full KYC, a frozen wallet gets no form.
- `TopupFlow.test.tsx` (15):
  - Checkout opens with the order and public key.
  - Polling stops on CAPTURED (and doesn't poll again) and on timeout with "We'll confirm it shortly", and "Check
    again" can still finish.
  - It polls through a failed read, and stops on FAILED, EXPIRED and PARKED.
  - A dismissed Checkout doesn't poll.
  - A retry reuses the key and a new amount gets a new one; a double click creates one top-up.
  - LIMIT_EXCEEDED, a Checkout script that doesn't load, an invalid amount, the room hint and `roomLeft`.
- `PrivacyPage.test.tsx` (11):
  - The consent list; marketing posts `{purpose, policyVersion: current, granted}`; a failed save leaves the switch
    unchanged.
  - Export: PENDING, then polled, then the link with expiry; REQUEST_IN_PROGRESS; a `javascript:` URL is never a link.
  - Erase: stays disabled for `erase` and `ERASE ` and Enter doesn't submit; only `ERASE` sends.
  - Erasure refusals WALLET_NOT_EMPTY, AML_CASE_OPEN and WALLET_FROZEN; the erased state signs out.
- `router.test.tsx`: the three routes are customer-only nav links and open with the TEST MODE banner.
- Playwright `e2e/transfer.spec.ts` (`@stack`):
  - customer2 reads their wallet ID.
  - customer1 sends ₹100 with a unique note and **double-clicks** Send: exactly one `POST /transfers` with a UUID key.
  - customer2's history shows the row as Received ₹100.00.

**Docs**
- ADR 0023: idempotency keys in the UI, and Razorpay Checkout (hosted script, CSP for Day 33).
- CLAUDE.md "Frontend": the Idempotency-Key and double-submit rule.
- README: what customers can do in the web app.

### Verification

- `npm run ci`: lint (0 warnings), type-check, **261 Vitest tests in 19 files** and the build all pass. Day 30 had
  207 tests in 16 files; this adds 48 in the three new files and 6 router rows.
- `npm run e2e` (no backend): 3 passed.
- `./mvnw verify`: BUILD SUCCESS, **2,457 tests, 0 failures**: event-contracts 36; core-app 1,372 unit and 831
  integration; risk-aml-service 146 unit and 72 integration. The backend is unchanged, so the numbers match Day 30.
- `npm run e2e:stack` against the local stack (core-app `local,demo`, the AML service `local`, Keycloak):
  - The two Day 29 login specs and Day 30's onboarding spec pass.
  - `transfer.spec.ts` passed 5 of 6 runs (one, then `--repeat-each=4`). It failed once, on the first run after
    the apps started.
  - In that run the transfer itself succeeded: exactly one POST, "Money sent", and the row is in
    `payments.transfers`. But customer2's history page didn't show it within 10 s.
  - The trace shows the history GET started about 30 ms after the transfer's `created_at`. Calling the API
    afterwards as customer2 returned the transfer.
  - `TransferService.transfer` is `@Transactional` and `open-in-view` is off, so the commit should come before the
    response. I couldn't reproduce it again, so the cause is not proven. See "Not done".

### Not done / noted

- **The real test-mode top-up through the Day 14 tunnel** (build plan "Check") needs Razorpay test keys, the tunnel
  and a person paying in Checkout. It is left for you. Locally without the tunnel, no webhook arrives: the page shows
  "We'll confirm it shortly" and the reconciler settles the top-up after 15 minutes.
- **The DevTools-throttled double click** (build plan "Check") is covered by `e2e/transfer.spec.ts` (a real double
  click, one POST) and by the unit test that holds the server open. Doing it by hand in DevTools is still worth it
  for the demo.
- **The add-money hint ignores open top-ups.** The backend reserves room under the cap for CREATED/PENDING top-ups,
  but `limit-usage` doesn't report them. The server's LIMIT_EXCEEDED message covers the difference.
- **One unexplained e2e failure** (see Verification). If it shows up again, keep the trace and compare the history
  response body with the transfer's commit time. A plausible but unproven cause is a read on a cold JVM racing the
  commit. Each run sends ₹100 from customer1, who starts with ₹50,000 after a demo reseed.
- **No CSP on the web app yet.** Day 33 adds it, and it must allow Razorpay (see ADR 0023).
