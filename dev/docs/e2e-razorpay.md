# Manual end-to-end test: a Razorpay test-mode top-up through a tunnel

This proves the M7 "Done when" item **"One manual end-to-end test payment in Razorpay test mode works"**:
a real Razorpay test payment → signed `payment.captured` webhook over the public internet → wallet credited →
balanced journal entry.

**Test mode only.** `RAZORPAY_KEY_ID` must start with `rzp_test_` (core-app refuses to start otherwise). Only
fake users (`customer1`). No real money moves.

Commands are run from `dev/`. Shell blocks marked **Git Bash** need Git Bash (they use `source`, `$(...)`);
everything else works in PowerShell too.

```
 browser (localhost:8080/checkout.html) ──POST /api/v1/topups──▶ core-app ──create order──▶ Razorpay (test)
        │                                                                                        │
        └──── Razorpay Checkout popup (pay with test UPI / netbanking) ─────────────────────────▶│
                                                                                                 │
 core-app ◀── localhost:8080 ◀── tunnel ◀── https://<random>.trycloudflare.com ◀── webhook ──────┘
   └─ verify HMAC → store webhook_events → TopupSettlementService: Dr GATEWAY_CLEARING / Cr WALLET:<id>
```

The browser talks to `localhost` directly. The tunnel exists **only** so Razorpay's servers can reach the
webhook endpoint.

---

## 0. One-time setup

| # | What | How to check |
|---|---|---|
| 0.1 | `.env` exists (copy of `.env.example`) with real values for every `change-me` | `core-app` starts (step 2) |
| 0.2 | **Razorpay test keys** in `.env`: Dashboard → switch to **Test Mode** → Account & Settings → API Keys → generate. Put them in `RAZORPAY_KEY_ID` (`rzp_test_...`) and `RAZORPAY_KEY_SECRET`. | Step 5 returns a `gatewayOrderId` |
| 0.3 | **`RAZORPAY_WEBHOOK_SECRET`** in `.env`: any long random string you choose (`openssl rand -hex 32`). You type the same value into the dashboard in step 4. | Step 7: no `invalid signature` in the log |
| 0.4 | **Payment capture = automatic** (test mode): Dashboard → Account & Settings → Payment capture. Our order request does not send `payment_capture`, so the account setting decides. If it's manual, payments stop at `authorized`, and `payment.captured` never fires. | Step 8: payment status `captured` in the dashboard |
| 0.5 | **Tunnel tool**: `winget install --id Cloudflare.cloudflared` (quick tunnels need no account). ngrok works too (`ngrok http 8080`). | `cloudflared --version` |
| 0.6 | **IntelliJ HTTP client** set up as in the header of `http/auth.http`: `http/http-client.private.env.json` has the dev password; environment **local** selected. | `customer1: token` → 200 |
| 0.7 | **customer1 has a MIN wallet**: in `http/auth.http` run, once, in order: `customer1: token`, `own profile` (creates the user), the consents `POST` (TERMS/PRIVACY), `customer1: minimum KYC`. | `GET /api/v1/wallets/me` (`http/wallet.http`) → 200, `tier MIN`, `status ACTIVE` |

---

## 1. Start the infrastructure

```powershell
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
```

Wait until `postgres`, `redis`, `kafka`, `keycloak` and `localstack` show `(healthy)`. Keycloak takes about 30–60 s.

## 2. Start core-app (local profile)

```powershell
./mvnw install -DskipTests            # once, or after changing event-contracts
./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"
```

Keep this terminal visible: **it is where you watch the webhook arrive**. Ready when you see
`Started TrustLedgerApplication`. Check: `http://localhost:8080/actuator/health` → `{"status":"UP"}`.

## 3. Start the tunnel

In a second terminal:

```powershell
cloudflared tunnel --url http://localhost:8080
```

It prints `https://<random-words>.trycloudflare.com`. Call it `TUNNEL` below. **The URL changes every time you
restart cloudflared.** When it changes, update the webhook URL in step 4.

Check the tunnel reaches the webhook endpoint (third terminal):

```bash
curl -i https://TUNNEL/actuator/health                                    # 200 {"status":"UP"}
curl -i -X POST https://TUNNEL/api/v1/webhooks/razorpay -H 'Content-Type: application/json' -d '{}'
# 400 ProblemDetail WEBHOOK_SIGNATURE_INVALID  -> the path works end to end (it stores one rejected row; that's fine)
```

## 4. Register the webhook in the Razorpay test dashboard

Dashboard (**Test Mode**) → Account & Settings → Webhooks → **Add new webhook** (or edit the existing one):

- **Webhook URL:** `https://TUNNEL/api/v1/webhooks/razorpay`
- **Secret:** exactly the value of `RAZORPAY_WEBHOOK_SECRET` in `.env` (no quotes, no trailing space)
- **Active events:** `payment.captured`, `payment.failed` (nothing else is needed; other types are stored and ignored)

Save.

## 5. Get a token for customer1

Tokens live **5 minutes**. Get a fresh one right before steps 6–7.

- **IntelliJ:** `http/auth.http` → run `### customer1: token`. This sets `{{customer1_token}}`. Copy the
  `access_token` value from the response panel too: the checkout page needs it.
- **Git Bash alternative:**

  ```bash
  set -a; source .env; set +a
  TOKEN=$(curl -s http://localhost:8180/realms/trustledger/protocol/openid-connect/token \
    -d grant_type=password -d client_id=trustledger-dev -d username=customer1 \
    --data-urlencode "password=$TRUSTLEDGER_DEV_USER_PASSWORD" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
  echo "$TOKEN"
  ```

## 6. Create the top-up and pay

### 6a. Create it in `http/topups.http`

1. **Change the `Idempotency-Key`** of `### customer1: create a ₹500 top-up` to a new UUID (any value you
   haven't used, e.g. bump the last digits). With the old key you get the **old** top-up back
   (`Idempotent-Replayed: true`), which may already be CAPTURED.
2. Run it → **201** `{status PENDING, gatewayOrderId "order_...", keyId "rzp_test_..."}`. The response handler
   stores `{{topup_id}}`. **Copy the `id`** (call it `TOPUP_ID`).
3. Optional: run `### customer1: read it back` → 200 PENDING, and `### customer2: someone else's top-up` → 404
   (the BOLA check). Rerunning the create request unchanged → the same body with `Idempotent-Replayed: true`, and
   no second Razorpay order.

### 6b. Pay it on `checkout.html`

1. Open **`http://localhost:8080/checkout.html`**. Use `localhost`, not the tunnel URL: the page's CSP only
   allows calls to its own origin.
2. Paste the token, paste `TOPUP_ID` into **"Or pay an existing PENDING top-up"**, and click **Pay existing
   top-up**. The page `GET`s the top-up (`-> 200`, PENDING) and opens Razorpay Checkout for its order. (Anything
   other than a PENDING top-up with an order is refused on the page. **Create top-up and pay** still does both
   steps in one click if you skip 6a.)
3. In the Razorpay popup, pay with a test method:
   - **UPI** → VPA `success@razorpay`, or
   - **Netbanking** → any bank → on the mock bank page click **Success**, or
   - a domestic test card from Razorpay's docs ("Test Card Details"); any future expiry, any CVV, OTP as the page says.
4. The page logs `Checkout success` with a `razorpay_payment_id`. It then calls `GET` once. That call may still
   show `PENDING`, because the webhook can arrive a second or two later. Click **Refresh status**.

## 7. Watch the webhook arrive

**core-app terminal**, within a few seconds of paying:

```
Top-up <TOPUP_ID> captured, payment pay_..., journal entry <ENTRY_ID>
Razorpay webhook <event id> (payment.captured): PROCESSED
```

**Razorpay dashboard** → Webhooks → your webhook → delivery log: `payment.captured`, response **200**.

If the same event is delivered again (Razorpay retry, or you resend it), you see `...: DUPLICATE`, and nothing is
credited twice.

## 8. Check balance and journal entry

### Through the API

- `http/wallet.http` → `### customer1: own wallet` → `balance` went up by `100.00`.
- `http/topups.http` → read it back → `status CAPTURED`, `gatewayPaymentId pay_...`.

### With SQL

```powershell
docker compose -f infra/docker-compose.yml exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Inside psql, set the top-up ID once. All later queries use it:

```sql
\set topup_id 'PASTE-TOPUP_ID-HERE'

-- 1. The top-up: CAPTURED, with payment ID and journal entry ID
SELECT id, status, amount_minor, gateway_order_id, gateway_payment_id, journal_entry_id, created_at, updated_at
  FROM payments.topups WHERE id = :'topup_id';

-- 2. The webhook deliveries: signature_valid = t, processed_at set
SELECT gateway_event_id, event_type, signature_valid, received_at, processed_at
  FROM payments.webhook_events ORDER BY received_at DESC LIMIT 5;

-- 3. The journal entry: two lines, Dr PLATFORM:GATEWAY_CLEARING / Cr WALLET:<wallet id>, both 10000 paise
SELECT e.id AS entry_id, e.entry_type, e.reference_type, e.posted_at,
       l.line_no, a.code, l.direction, l.amount_minor
  FROM ledger.journal_entries e
  JOIN ledger.journal_lines  l ON l.entry_id = e.id
  JOIN ledger.ledger_accounts a ON a.id = l.account_id
 WHERE e.reference_type = 'TOPUP' AND e.reference_id = :'topup_id'
 ORDER BY l.line_no;

-- 4. Wallet balance vs its ledger account (they must be equal; this is what the nightly reconciliation checks)
SELECT w.id AS wallet_id, w.status, w.balance_minor,
       (SELECT coalesce(sum(CASE l.direction WHEN 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0)
          FROM ledger.journal_lines l WHERE l.account_id = w.ledger_account_id) AS ledger_balance_minor
  FROM wallet.wallets w
 WHERE w.id = (SELECT wallet_id FROM payments.topups WHERE id = :'topup_id');
```

Expected: query 1 → `CAPTURED`, `amount_minor 10000`. Query 3 → exactly **one** entry with two lines that
balance. Query 4 → `balance_minor = ledger_balance_minor`.

`\q` leaves psql.

---

## 9. Optional extra runs

| Run | How | Expected |
|---|---|---|
| Failed payment | Pay with UPI `failure@razorpay` (or netbanking → **Failure**) | Log `Top-up ... failed, payment ...`; `status FAILED`; no journal entry |
| Late capture | After a failure, retry **in the same popup** with `success@razorpay` | `CAPTURED` after all (late capture is credited by design) |
| Reconciler (lost webhook) | Stop cloudflared, pay a new top-up, wait 15–20 min | Log `Pending top-up reconciliation: ...`; then `CAPTURED`. Restart the tunnel later and Razorpay's retried webhook logs `already settled` / `DUPLICATE` |
| Over the limit | Not with a fresh MIN wallet. Creation already refuses more than ₹10,000 (422 `LIMIT_EXCEEDED`). Covered by `TopupWebhookSettlementIT` | — |

## 10. Clean up

- Stop cloudflared (Ctrl+C). While it runs, **your whole local app is public**. The API needs a token, but
  Swagger UI and the checkout page are open under the `local` profile.
- In the dashboard, disable the webhook, or leave it: the old tunnel URL just stops answering.
- Stop the app (Ctrl+C) and, if you like, `docker compose -f infra/docker-compose.yml stop`.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| core-app won't start, message about the Razorpay key | `RAZORPAY_KEY_ID` is not `rzp_test_...`, or the secret is blank | Fix `.env` (step 0.2) |
| `POST /topups` → 401 | Token expired (5 min) or copied with spaces | Fresh token (step 5) |
| `POST /topups` → 403 `KYC_REQUIRED` / 404 wallet | customer1 has no wallet yet | Step 0.7 |
| `POST /topups` → 503 `GATEWAY_UNAVAILABLE` | Razorpay rejected the keys or isn't reachable. After 10 failures the circuit stays open for 30 s | Check the keys; wait 30 s |
| Popup doesn't open; console shows a CSP error | Page opened via the tunnel or another host | Use `http://localhost:8080/checkout.html` |
| Paid, still PENDING, **no webhook line in the log** | Webhook URL wrong or old (tunnel restarted), event not ticked, or capture is manual (step 0.4) | Dashboard delivery log shows what Razorpay saw; fix URL / events / capture setting |
| Log: `Rejected a Razorpay webhook: invalid signature` | Dashboard secret ≠ `RAZORPAY_WEBHOOK_SECRET` (or `.env` changed without restarting the app) | Make them equal; restart the app; resend the event |
| Log: `Captured payment ... for unknown order` | The payment's order isn't in this database (an old order, or another DB) | Create a new top-up and pay that one |
| Status `PARKED` | Capture-time limit check failed (balance cap, monthly load, wallet frozen): ADR 0006 | Expected behaviour; see `docs/adr/0006-over-limit-topups.md` |
