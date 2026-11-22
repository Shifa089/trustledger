# AML demo: three ₹46,000 top-ups freeze the wallet (R2 → freeze request → WALLET_FROZEN)

This shows M9 end to end: customer1 (FULL tier) adds ₹46,000 three times. The AML service sees three transactions
in R2's band (₹45,000–₹49,999.99, "structuring": just under the ₹50,000 high-value hold) within 24 hours. It raises a
**HIGH** alert and sends `AccountFreezeRequestedV1`, and core-app freezes the wallet. customer1's next transfer fails
with **409 `WALLET_FROZEN`**. Then a compliance officer works the case and downloads the STR report.

**Local only. Razorpay test mode only. Fake users only.**

Requests: `http/aml-demo.http` (IntelliJ HTTP client). The faster payment path uses `scripts/aml-demo-webhooks.sh`
(Git Bash). Commands run from `dev/`.

```
customer1 ─POST /topups x3─▶ core-app ─(paid: payment.captured webhook)─▶ wallet credited + TopupCaptured
                                │ outbox (event_publication)
                                ▼
          trustledger.payments.topup-captured.v1 ──▶ risk-aml-service: processed_events + transaction_facts
                                                         │ RuleEngine: R2 (3 in band within 24 h) → HIGH
                                                         │ case + alert + aml.freeze_requests (same transaction)
                                                         ▼ FreezeRequestRelay (every 1 s)
          trustledger.aml.account-freeze-requested.v1 ──▶ core-app: wallet.processed_events + freeze (source AML)
                                                         │ WalletFrozen → e-mail (Mailpit) and
                                                         ▼
          trustledger.wallet.wallet-frozen.v1           customer1: POST /transfers → 409 WALLET_FROZEN
```

On a laptop the whole chain takes about a second. One correlation ID (from the third webhook request) appears on
all three Kafka records and in both apps' logs.

---

## 0. One-time setup

| # | What | How to check |
|---|---|---|
| 0.1 | `.env` complete, with Razorpay **test** keys and `RAZORPAY_WEBHOOK_SECRET` (`docs/e2e-razorpay.md` §0) | core-app starts |
| 0.2 | IntelliJ HTTP client: `http/http-client.private.env.json` with the dev password, environment **local** (`http/auth.http` header). `http-client.env.json` has `api` (8080) and `aml` (8081) | `customer1: token` → 200 |
| 0.3 | **customer1 at FULL KYC**: `http/auth.http` (token, `own profile`, consents, minimum KYC), then `http/kyc.http` top to bottom (officer1 verifies, officer2 approves) | `GET /wallets/me` → `tier FULL`, `maxBalance 200000.00` |
| 0.4 | **customer2 with a wallet** (the transfer's recipient): as customer2, `own profile`, consents and minimum KYC (use another fake PAN, e.g. `ABCPT5678G`) | `GET /wallets/me` as customer2 → 200 |
| 0.5 | Staff one-time codes: `scripts/aml-demo-webhooks.sh totp` prints the current code (no `oathtool` needed). One login per user per code; codes last 30 s | officer token → 200 |

Why FULL: a MIN wallet may hold and add only ₹10,000 a month, so a ₹46,000 top-up is refused (422
`LIMIT_EXCEEDED`). FULL allows ₹2,00,000 a month; the demo adds ₹1,38,000. **So it runs once per calendar month per
wallet**; see §9 for a second run.

> **Old local database?** A wallet created before the ledger existed (local DB from M2/M3 days, before Flyway V7)
> has no ledger account. Its first credit then fails with 404 and the log line
> `Request rejected with NOT_FOUND` (cause: `Ledger account WALLET:... does not exist`). Check:
> `SELECT id FROM wallet.wallets WHERE ledger_account_id IS NULL;`. Fix (local only):
> ```sql
> BEGIN;
> INSERT INTO ledger.ledger_accounts (id, code, type, normal_side, currency, created_at)
> SELECT gen_random_uuid(), 'WALLET:' || w.id, 'LIABILITY', 'CREDIT', w.currency, now()
>   FROM wallet.wallets w WHERE w.ledger_account_id IS NULL;
> UPDATE wallet.wallets w SET ledger_account_id = a.id
>   FROM ledger.ledger_accounts a WHERE w.ledger_account_id IS NULL AND a.code = 'WALLET:' || w.id;
> COMMIT;
> ```
> Only correct while those wallets have balance 0, which a wallet without a ledger account always has. Or start
> clean: `docker compose -f infra/docker-compose.yml down -v` (deletes **all** local data) and redo §0.

## 1. Start everything

```powershell
docker compose -f infra/docker-compose.yml up -d          # wait for (healthy)
./mvnw install -DskipTests                                # once, or after changing event-contracts
./mvnw -pl core-app spring-boot:run "-Dspring-boot.run.profiles=local"            # terminal 1
./mvnw -pl risk-aml-service spring-boot:run "-Dspring-boot.run.profiles=local"    # terminal 2
```

Ready: `Started TrustLedgerApplication` / `Started RiskAmlApplication`. core-app creates the Kafka topics (local
profile).

## 2. Watch Kafka (terminal 3)

One consumer on the three topics of the chain, with headers and key. A new group name each time, so it starts from the
beginning. In **PowerShell**:

```powershell
docker compose -f infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server localhost:9092 `
  --include 'trustledger\.(payments\.topup-captured|aml\.account-freeze-requested|wallet\.wallet-frozen)\.v1' `
  --property print.topic=true --property print.key=true --property print.headers=true `
  --group "demo-watch-$(Get-Random)"
```

In **Git Bash**, put `MSYS_NO_PATHCONV=1` in front (otherwise Git Bash rewrites `/opt/kafka/...` into a Windows path
and the exec fails with `no such file or directory`), use `\` for line breaks and `demo-watch-$RANDOM`.

Leave it running. Consumer groups and lag, any time:

```powershell
docker compose -f infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh `
  --bootstrap-server localhost:9092 --describe --all-groups
```

`aml-topup-captured`, `aml-transfer-completed` (AML) and `core-account-freeze-requested` (core-app) should show
`LAG 0` once things settle. Dead letters, if anything goes wrong: the same `kafka-console-consumer.sh` on
`<topic>.DLT`.

## 3. Create three ₹46,000 top-ups

`http/aml-demo.http`: requests **1–4** (tokens; wallets: customer1 `FULL`, `ACTIVE`), then **5a, 5b, 5c**. Each →
**201** `{status PENDING, gatewayOrderId "order_..."}`. A real Razorpay **test** order is created for each; the IDs are
stored as `{{topup1..3}}`, and 5c's response handler prints the script command for §4B in the HTTP client's log.

The `Idempotency-Key` is `{{$random.uuid}}`, so every run creates a new top-up. Run each request **once**.

## 4. Pay them: pick one

### 4A. For real, in Razorpay test mode (slow: tunnel and dashboard)

Follow `docs/e2e-razorpay.md` §3–4 (cloudflared tunnel, webhook registered with your secret), then for each top-up:
`http://localhost:8080/checkout.html` → paste token and top-up ID → **Pay existing top-up** → UPI
`success@razorpay`. Razorpay sends the signed `payment.captured` webhook.

### 4B. Fast: signed webhooks from a local script

```bash
# Git Bash, from dev/
scripts/aml-demo-webhooks.sh capture TOPUP1_ID TOPUP2_ID TOPUP3_ID
```

For each top-up the script reads its order and amount through the API (as customer1), builds a `payment.captured`
body for it with a made-up `pay_Demo...` payment ID, signs those exact bytes with `RAZORPAY_WEBHOOK_SECRET` (hex
HMAC-SHA256, as Razorpay does) and posts it with `X-Razorpay-Signature` and a new `X-Razorpay-Event-Id`. core-app
can't tell it from Razorpay's own webhook, which is the point: **it exercises the same verification and settlement
code**. It refuses to run unless the API is `localhost`/`127.0.0.1` and `RAZORPAY_KEY_ID` is a `rzp_test_` key, and
it never prints the secret or the token.

```
top-up 01a20271-...6495: payment.captured pay_Demo6355... (46000.00 INR), event evt_demo_cb4d... -> HTTP 200
top-up 01a20271-...6d3a: payment.captured pay_Demo46d1... (46000.00 INR), event evt_demo_dc47... -> HTTP 200
top-up 01a20271-...ef63: payment.captured pay_Demodc67... (46000.00 INR), event evt_demo_6ac7... -> HTTP 200
```

The Razorpay dashboard still shows those orders as unpaid. That's expected: no payment was made there.

## 5. Watch the chain

### Kafka (terminal 3)

Three `TopupCapturedV1`, then one `AccountFreezeRequestedV1` and one `WalletFrozenV1`, all keyed by the wallet ID
(headers, key, value; shortened):

```
__TypeId__:...TopupCapturedV1,eventId:01a20272-c8ae-...,correlationId:a4201e2a-...   <wallet>  {"topupId":"...ef63","amountMinor":4600000,...}
eventId:01a20272-c956-...,correlationId:a4201e2a-...,__TypeId__:...AccountFreezeRequestedV1  <wallet>  {"caseId":"01a20272-c94e-...","ruleCode":"R2",...}
__TypeId__:...WalletFrozenV1,eventId:01a20272-cba8-...,correlationId:a4201e2a-...    <wallet>  {"source":"AML",...}
```

Same `correlationId` on the last three: the request ID of the third webhook travelled through core-app's outbox, the
AML consumer, the freeze outbox, and back into core-app.

### Logs

- core-app: `Top-up ... captured, payment pay_Demo..., journal entry ...` and `Razorpay webhook evt_demo_... (payment.captured): PROCESSED` (x3).
- risk-aml-service: `Recorded top-up fact eventId=...` (x3), `AML alert raised rule=R2 severity=HIGH wallet=... case=...`,
  `Freeze requested ... request=...`, and about a relay interval later `Freeze request sent request=...`.
- core-app: `Wallet ... frozen by AML case=... rule=R2 eventId=...`.

### SQL

```powershell
docker compose -f infra/docker-compose.yml exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

```sql
\set wallet_id 'PASTE-CUSTOMER1-WALLET-ID'

-- 1. core-app: the three top-ups, CAPTURED, each with a journal entry
SELECT id, status, amount_minor, gateway_payment_id, journal_entry_id IS NOT NULL AS posted
  FROM payments.topups WHERE wallet_id = :'wallet_id' ORDER BY created_at;

-- 2. core-app: the webhook deliveries: signature_valid t, processed t
SELECT gateway_event_id, event_type, signature_valid, processed_at IS NOT NULL AS processed
  FROM payments.webhook_events ORDER BY received_at DESC LIMIT 5;

-- 3. AML: the facts it learned from Kafka: three IN / TOPUP of 4600000
SELECT direction, source, amount_minor, occurred_at
  FROM aml.transaction_facts WHERE wallet_id = :'wallet_id' ORDER BY occurred_at;

-- 4. AML: the case and the R2 HIGH alert
SELECT c.id AS case_id, c.status, a.rule_code, a.severity, a.details, a.created_at
  FROM aml.cases c JOIN aml.alerts a ON a.case_id = c.id WHERE c.wallet_id = :'wallet_id';

-- 5. AML outbox: the freeze request. id = the Kafka eventId; published_at set = the broker acknowledged it
SELECT id AS event_id, case_id, rule_code, requested_at, published_at, attempts
  FROM aml.freeze_requests WHERE wallet_id = :'wallet_id';

-- 6. core-app inbox: the same eventId, handled once
SELECT event_id, processed_at FROM wallet.processed_events ORDER BY processed_at DESC LIMIT 3;

-- 7. core-app: the wallet FROZEN by AML, with the case in the reason
SELECT w.status, w.balance_minor, f.source, f.reason, f.frozen_at
  FROM wallet.wallets w JOIN wallet.wallet_freezes f ON f.wallet_id = w.id
 WHERE w.id = :'wallet_id' AND f.unfrozen_at IS NULL;
```

What one run gave:

| Query | Result |
|---|---|
| 1 | 3 × `CAPTURED`, `4600000`, `pay_Demo...`, posted `t` |
| 3 | 3 × `IN`, `TOPUP`, `4600000`, at 08:50:05.02, 05.81, 06.63 |
| 4 | `OPEN`, `R2`, `HIGH`, `3 transactions between 45000.00 and 49999.99 in PT24H (at least 3); latest TOPUP ...` |
| 5 | requested 08:50:06.692, **published 08:50:07.379**, attempts 1 |
| 6 | the same `event_id`, processed 08:50:07.385 |
| 7 | `FROZEN`, `13800000`, `AML`, `AML case 01a20272-c94e-..., rule R2` |

Mailpit (`http://localhost:8025`) has the "wallet frozen" e-mail for customer1, without reason or source.

## 6. The point: the next transfer fails

`http/aml-demo.http` **7** → `status FROZEN`, `balance "138000.00"`. Then:

- **9** (customer1 → customer2, ₹100) → **409**:

  ```json
  {"title":"Wallet frozen","status":409,"errorCode":"WALLET_FROZEN","detail":"Your wallet is frozen.", ...}
  ```

  No transfer row is written: `SELECT count(*) FROM payments.transfers WHERE sender_wallet_id = :'wallet_id';` → 0.
- **8** (another top-up) → **409 `WALLET_FROZEN`** too, before any Razorpay order is created.

## 7. The officer's side (risk-aml-service, port 8081)

`http/aml-demo.http` **10–17**. officer1 needs a fresh code: `scripts/aml-demo-webhooks.sh totp` → paste into `@otp`.

| Request | Result |
|---|---|
| 11 list `status=OPEN&walletId=...` | 200, one case, `highestSeverity HIGH`, `alertCount 1` |
| 12 case detail | 200: `alerts` [R2 HIGH], `freezeRequests` [`publishedAt` set], `facts` [3 × IN 46000.00] |
| 13 STR report now | **409** `INVALID_STATE_TRANSITION`: only an ESCALATED case has one |
| 14 assign (`{}` = to me) | 200 `INVESTIGATING`, `assignedTo` = officer1 |
| 15 escalate with notes | 200 `ESCALATED` |
| 16 STR report | 200 `text/csv`, `Content-Disposition: attachment`, `Cache-Control: no-store` |
| 17 customer1 on the AML API | 403 |

The CSV (wallet, officer and reference IDs masked to the last 4 characters, the case ID whole):

```
record_type,case_id,case_status,wallet,officer,occurred_at,rule_code,severity,direction,source,counterparty,reference,amount,currency,details
CASE,01a20272-...,ESCALATED,****2b13,****1ae3,2026-11-22T08:50:06.691936Z,,,,,,,,,"Three top-ups of 46,000 in one day, ..."
ALERT,01a20272-...,ESCALATED,****2b13,,2026-11-22T08:50:06.691936Z,R2,HIGH,,,,,,,3 transactions between ... latest TOPUP ****ef63
FACT,01a20272-...,ESCALATED,****2b13,,2026-11-22T08:50:05.021065Z,,,IN,TOPUP,,****6495,46000.00,INR,
...
```

Closing the case as `FALSE_POSITIVE` instead would **not** unfreeze the wallet (ADR 0007 amendment): that stays a
separate officer decision in core-app.

## 8. Unfreeze (optional)

As an officer (any officer except the wallet's owner) in core-app:

```http
POST {{api}}/api/v1/compliance/wallets/{{sender_wallet_id}}/unfreeze
Authorization: Bearer {{officer1_token}}
Content-Type: application/json

{"reason": "AML demo finished"}
```

→ 200 `ACTIVE`; the freeze row gets `unfrozen_at`. The old freeze request is **not** applied again even if Kafka
redelivered it: core-app skips it by `eventId` (`wallet.processed_events`).

## 9. Running it again

- **Same month, same wallet:** the fourth ₹46,000 still fits (₹1,84,000 of ₹2,00,000), a fifth doesn't (422
  `LIMIT_EXCEEDED`). And the wallet may hold at most ₹2,00,000. Either unfreeze (§8) and move money out first, or
  use a clean database (`docker compose ... down -v`, redo §0).
- **Within 24 hours of the R2 alert:** no second R2 alert for that wallet. The `RuleEngine` raises a rule at most
  once per wallet per rule window (R2: 24 h), so a fourth top-up in the band after an unfreeze does **not** freeze
  the wallet again. After the window, three new in-band transactions raise a new alert (on the wallet's unfinished
  case, or a new case if the old one is closed or escalated) and a new freeze request with a new `eventId`.
- **Easiest repeat:** another FULL customer, or a clean database.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `POST /topups` → 422 `LIMIT_EXCEEDED` | MIN wallet, or this month's ₹2,00,000 already used | §0.3, §9 |
| `POST /topups` → 503 `GATEWAY_UNAVAILABLE` | Razorpay test keys wrong, or no internet: creating a top-up creates a real test order | Check `.env`; `docs/e2e-razorpay.md` troubleshooting |
| Script: `refusing: ...` | API not on localhost, or not a `rzp_test_` key | By design |
| Script: HTTP 400 | `RAZORPAY_WEBHOOK_SECRET` in `.env` changed after core-app started | Restart core-app |
| Script: HTTP 404, log `Request rejected with NOT_FOUND` | Wallet without a ledger account (old local DB) | Box under §0. The failed event stays in `webhook_events` with `processed_at` NULL; harmless, rerun the script (new event ID) |
| Script: `top-up ... is CAPTURED, not PENDING: skipped` | Already paid | Create new top-ups (§3) |
| Wallet stays ACTIVE | AML service not running, or a record went to a `.DLT` topic | AML log; `kafka-consumer-groups.sh` lag; read the `.DLT` topics |
| `publishedAt` stays null | Kafka down for the AML service | Comes out by itself when Kafka is back (the relay retries every second) |
| Kafka exec: `no such file or directory` with a `D:/.../opt/kafka` path | Git Bash path rewriting | `MSYS_NO_PATHCONV=1` (§2) |
| officer token → 400 `invalid_grant` | Code expired or already used by that user | New code (`totp`), within 30 s |
