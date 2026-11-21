# Changes

## DAY14 Session A: Milestone M7 manual end-to-end runbook (`docs/e2e-razorpay.md`) + pay an existing top-up on `checkout.html`

**Goal:** a written, repeatable procedure for the last M7 "Done when" item: *one manual end-to-end test payment
in Razorpay test mode works*. The flow: a real test-mode payment reaches the app as a signed webhook through a
public tunnel, credits the wallet, and leaves a balanced journal entry you can see with SQL.

Branch: `feature/m7-e2e-runbook` (created from `master`), merged into `master`.

Decisions:
1. **Tunnel: Cloudflare quick tunnel (`cloudflared tunnel --url http://localhost:8080`).** No account and no new
   project dependency (it's a machine tool, like Docker). ngrok is listed as an alternative. The browser still
   uses `localhost`; the tunnel only carries Razorpay → webhook.
2. **`checkout.html` can now pay an existing top-up.** Before, the page could only create a **new** top-up and
   pay that one, so a top-up created in `http/topups.http` could never be paid. Now there is an optional
   "existing top-up ID" field. The page `GET`s that top-up and opens Razorpay Checkout for its order. Only the
   front end changed: `GET /api/v1/topups/{id}` already returned `keyId` and `gatewayOrderId`, and it is
   owner-checked (another user's ID → 404).
3. **Only PENDING top-ups are reopened on the page.** FAILED/EXPIRED top-ups could in theory still be paid on
   the same order (late capture is credited, Day 13 B). For a manual test page, "create a new one" is simpler
   and can't confuse a test run.
4. **The ID is checked as a UUID before it goes into the URL path.** Without the check, a value like `../wallets/me`
   would make the page call another endpoint with your token.

---

### 1. How to verify Day 14 Session A

Run these from `dev/`. Start **Docker Desktop** first. One command per cell.

> **PowerShell:** put every `-D...` argument that contains a dot or a comma in double quotes, as below.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `./mvnw clean verify` | Full build, **green**. |
| 2 | `./mvnw -pl core-app test "-Dtest=CheckoutPageLocalTest"` | The page has the new field and button. The script checks the UUID, refuses non-PENDING top-ups and wires the button. CSP and "API not loosened" still hold. |
| 3 | Manual: follow `docs/e2e-razorpay.md` steps 1–8 | The real payment end to end: tunnel, webhook, `CAPTURED`, balance, journal entry, wallet = ledger. |

**Results:** `./mvnw clean verify` is **BUILD SUCCESS** (5 min 05 s): **993 unit tests** (+1) and
**590 integration tests**, all green. Details in section 5. The manual run (row 3) is the next step, done
together after this merge.

---

### 2. What changed and why

#### Docs

| File | What |
|---|---|
| `docs/e2e-razorpay.md` | **New.** Covers: one-time setup (test keys, webhook secret, **automatic payment capture**, cloudflared, customer1's wallet); start infra and app; start and check the tunnel; register the webhook (URL, secret, events); token; create in `topups.http` (with a **new Idempotency-Key**); pay on `checkout.html`; the log lines to watch; API checks. Four SQL queries: top-up, webhook rows, journal lines, and wallet vs ledger balance. Also optional runs (failed, late capture, reconciler), cleanup and a troubleshooting table. |
| `http/topups.http` | Header now points to "Pay existing top-up" and the runbook. |

#### Checkout page (local profile only)

| File | What |
|---|---|
| `checkout/checkout.html` | `+` input `#existing` and button `#payExisting`. |
| `checkout/checkout.js` | `openCheckout(topup)` pulled out and shared by both buttons. New `payExisting()`: token required → **UUID check** → `GET /api/v1/topups/{id}` → must be **PENDING with an order** → open Checkout. Click handlers go through one `onClick` helper that logs errors. |

#### Tests

| Test | Kind | Covers |
|---|---|---|
| `CheckoutPageLocalTest` (4, +1) | Unit (WebMvc slice) | `pageCanPayAnExistingPendingTopup`: the page has the field and button; the script contains the UUID guard, the PENDING-only rule and the button wiring. |

Honest limit: the page logic is tested by **content**, not by running the JavaScript. Running it would need a
JS test runner or a browser test (Playwright), which are new dependencies (ADR + approval). The manual runbook
is what really exercises it.

---

### 3. Machine setup for Day 14 Session A (not in Git)

- `cloudflared` installed (`winget install --id Cloudflare.cloudflared`).
- `.env`: `RAZORPAY_WEBHOOK_SECRET` set to a real random value, identical to the secret in the Razorpay test
  dashboard webhook.
- Razorpay test dashboard: payment capture **Automatic**; webhook `https://<tunnel>/api/v1/webhooks/razorpay` with
  `payment.captured` and `payment.failed`. Update the URL every time the quick tunnel restarts.

### 4. Deferred

- **The manual run itself** (next part of this session), then mark M7 "Done when" complete.
- A stable tunnel hostname (a named Cloudflare tunnel) so the dashboard URL doesn't change every run. It needs a
  Cloudflare account and domain, and isn't worth it for a one-off check.
- Browser-level tests for `checkout.js` (Playwright), only if the page grows beyond a test aid.
- Still open from Day 13: an IP rate limit for anonymous webhook calls; retention of rejected webhook rows;
  settling PARKED top-ups.

---

### 5. Results

`./mvnw clean verify -fae` ended in **BUILD SUCCESS** (total 5 min 05 s): core-app **993 unit / 590 integration**
tests, risk-aml-service green, `ModularityTests` and ArchUnit green.

---

### What to learn from this

- **A tunnel is a public door to your laptop, not a webhook pipe.** Everything on `localhost:8080` becomes
  reachable while it runs. The webhook is safe because of the HMAC signature, not because the URL is obscure.
  Stop the tunnel when you're done.
- **"Works in tests" ≠ "works with the real provider".** WireMock proves our logic. Only a real run proves the
  things we don't control: the dashboard secret, the capture setting, the event selection, the URL path. Most
  first-run failures are configuration, which is why the runbook has a troubleshooting table.
- **Build URLs from validated input only.** Putting user text straight into a path segment lets `../` turn one
  request into another, with the user's token attached. A strict format check (UUID) closes that before it
  starts.
- **Idempotency keys are sticky on purpose.** Re-running a saved request with the same key gives back the
  *first* result. That's correct for retries, but in manual testing you must change the key to get a new top-up.
