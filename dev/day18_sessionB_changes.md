# Changes

## DAY18 Session B (M9): the AML freeze demo, end to end

**Goal:** an end-to-end demo of M9 with `http/aml-demo.http` and `docs/aml-demo.md`:
1. customer1 (FULL tier) adds ₹46,000 three times, either paid through `checkout.html` in Razorpay test mode or,
   faster, with a local-only script that sends correctly signed `payment.captured` webhooks for three created
   top-ups;
2. the AML service raises R2 (HIGH) and sends a freeze request, and core-app freezes the wallet;
3. the next transfer fails with `WALLET_FROZEN`;
4. SQL and Kafka commands to watch each step; then run it together.

This is the M9 "Done when" item "end-to-end, 3 transfers of ₹46,000 within 24 hours freeze the sender's wallet",
shown with top-ups (R2 counts outgoing transfers and top-ups alike, SPEC §2).

Branch: `feature/day18b-aml-demo` (created from `master`), merged into `master`.

Decisions approved in the plan:
- **Git Bash script, no new dependencies.** `openssl` and `curl` are there; Python, `jq` and `oathtool` aren't.
- **The script only works locally.** It refuses unless the API is `localhost`/`127.0.0.1` and the key ID is a
  `rzp_test_` key. A forged "payment" must never be able to reach a shared environment.
- **A TOTP helper in the same script**, so officer logins work without `oathtool`.
- No Java changes: the demo uses only what Days 16–18A built.

### 1. `scripts/aml-demo-webhooks.sh`

- `capture TOPUP_ID...`. For each PENDING top-up of customer1, the script:
  - reads `gatewayOrderId` and the amount through the API with customer1's token (password from `.env`);
  - builds a Razorpay-shaped `payment.captured` body: made-up `pay_Demo<14 hex>` payment ID, amount in paise
    (integer arithmetic from `"46000.00"`, never floating point), `status captured`, no personal data;
  - signs **the exact bytes it sends** (`printf '%s'` piped to `openssl dgst -sha256 -hmac`, sent with
    `--data-binary`), the same way core-app's `RazorpaySignatureVerifier` checks them;
  - posts with `X-Razorpay-Signature` and a fresh `X-Razorpay-Event-Id`. It stops on anything but 200.

  So the demo exercises the real signature check, webhook storage and settlement code. Only Razorpay's side is
  replaced.
- `totp`: the current 6-digit code for the staff test users (RFC 6238: HMAC-SHA1 of the 30 s counter, dynamic
  truncation), from `TRUSTLEDGER_DEV_TOTP_SECRET`. I checked it against the RFC test vectors (`287082` at t=59,
  `081804` at t=1111111109).
- Never prints the webhook secret or a token. The repo's `.gitattributes` already checks out `*.sh` with LF, so it
  runs from a Windows checkout too.

### 2. `http/aml-demo.http`

Seventeen requests, top to bottom:
- tokens and both wallets;
- three ₹46,000 top-ups with `{{$random.uuid}}` idempotency keys (5c's handler logs the script command for its
  IDs);
- read back as CAPTURED, the wallet FROZEN;
- a fourth top-up and a ₹100 transfer, both **409 `WALLET_FROZEN`**;
- officer1 on the AML service:
  - list (`status`, `walletId`) and case detail;
  - STR export before escalation, **409**;
  - assign (`{}` = to me) and escalate;
  - the STR CSV;
  - a customer on the AML API, **403**.

`http-client.env.json` gained `"aml": "http://localhost:8081"`.

### 3. `docs/aml-demo.md`

A step-by-step guide in the style of `docs/e2e-razorpay.md`:
- diagram;
- one-time setup (FULL KYC for customer1, a wallet for customer2, staff codes);
- start-up;
- a Kafka console consumer on the three topics of the chain with headers and keys, consumer-group lag, and the DLTs;
- the two ways to pay;
- seven SQL queries, one per hop: top-ups, webhook events, `aml.transaction_facts`, case and alert,
  `aml.freeze_requests`, `wallet.processed_events`, and wallet plus freeze row;
- the failing transfer;
- the officer flow and the masked CSV;
- unfreezing and running it again;
- a troubleshooting table.

All outputs in it come from the actual run.

### 4. The run (2026-11-22, local)

| Time (UTC) | Step |
|---|---|
| 08:50:05.021 / 05.810 / 06.630 | three signed webhooks → three CAPTURED top-ups, three `TopupCapturedV1` |
| 08:50:06.681 | AML recorded the third fact |
| 08:50:06.692 | R2 HIGH alert, case `01a20272-c94e…`, freeze request in `aml.freeze_requests` (same transaction) |
| 08:50:07.379 | relay sent it (`published_at`); `AccountFreezeRequestedV1` on Kafka |
| 08:50:07.385 | core-app: `wallet.processed_events` row, wallet FROZEN, source AML, reason `AML case …, rule R2`; `WalletFrozenV1` |
| afterwards | ₹100 transfer → 409 `WALLET_FROZEN`; a fourth top-up → 409 `WALLET_FROZEN`; officer flow and STR CSV as expected |

- One correlation ID (`a4201e2a…`, the third webhook request's) appeared on the last `TopupCapturedV1`, on
  `AccountFreezeRequestedV1` and on `WalletFrozenV1`. Tracing works across both services and both outboxes.
- After the run, every consumer group showed lag 0.

**Found while running it: stale local data, not a code bug.**
- customer1's wallet was created on 2026-11-15, when the local DB was at Flyway V6, before the ledger existed. It
  had no ledger account.
- The first webhook therefore failed in `LedgerPostingService` with `NOT_FOUND` (`Ledger account WALLET:… does not
  exist`), shown as 404.
- Wallets created by today's code get their account; customer2's wallet, created today, has one.
- Fixed with an `INSERT` plus `UPDATE` on the local database only; the SQL and when it's safe (balance 0) are in
  `docs/aml-demo.md` §0. The failed delivery stays in `payments.webhook_events` unprocessed, which is harmless.

Two smaller environment notes went into the guide:
- Git Bash rewrites `/opt/kafka/...` in `docker compose exec` into a Windows path; use `MSYS_NO_PATHCONV=1`.
- `/proc/sys/kernel/random/uuid` doesn't exist on Windows; the run used `openssl rand` for idempotency keys.

### 5. Verification

`./mvnw verify` (all modules): **BUILD SUCCESS**, test counts unchanged from Session A:
- event-contracts: 31 unit tests;
- core-app: 1092 unit tests and 619 integration tests;
- risk-aml-service: 99 unit tests and 48 integration tests.

No production code changed in this session. The script and the guide were checked by running the demo itself
(section 4).

### Still open (M9 / later)

- In the demo, the FULL tier's monthly load limit (₹2,00,000) allows one run per wallet per month, and R2 alerts
  once per wallet per 24 h. The guide says how to rerun.
- The orphaned unprocessed webhook row from a failed delivery is never retried or reported; alerting on old
  unprocessed rows fits with M12 (Observability).
- Migrating data for wallets created before the ledger is not needed for anything but old local databases. No
  migration was written.

### What to learn from this

- **A demo is a test of the whole system that unit and integration tests can't give you.** Every IT passed, yet the
  first real run failed on old local data, and the guide now covers it.
- **Sign the bytes you send.** HMAC covers the exact body. Building the JSON once, signing it with `printf '%s'` and
  sending it with `--data-binary` keeps them identical. Re-serialising, or a trailing newline, breaks the signature.
- **Let a test tool stand in for one party only.** The script replaces Razorpay and nothing else, so signature
  checks, storage, settlement, both outboxes and the consumers all ran for real.
- **Follow one correlation ID through the system.** Putting it in every Kafka header made the chain readable in one
  console window.
