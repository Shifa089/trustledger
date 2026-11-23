# HTTP request collection

IntelliJ HTTP Client files for every endpoint in [SPEC §5](../docs/SPEC.md). Select the environment `local`
([http-client.env.json](http-client.env.json)). Copy
[http-client.private.env.json.example](http-client.private.env.json.example) to `http-client.private.env.json`
(git-ignored) and put the test-user password (`TRUSTLEDGER_DEV_USER_PASSWORD` from `.env`) in it. Tokens come from
the `trustledger-dev` client (password grant, local only) and live 5 minutes. Staff users (officer1, officer2,
admin1) need a fresh TOTP code in `@otp`: `scripts/aml-demo-webhooks.sh totp` prints one. Fake test users and data
only.

`HttpCollectionTest` (core-app and risk-aml-service) fails the build when a controller endpoint has no request here.

## Where to start

| File | What it shows |
| --- | --- |
| [demo.http](demo.http) | **Start here** with the `demo` profile: seeded FULL wallets, a transfer, its idempotent replay, history |
| [auth.http](auth.http) | Tokens for all six users, `/ping`, `/me`, consents, minimum KYC, 401/403, rate limit |
| [kyc.http](kyc.http) | Full KYC with documents, officer queue, maker (officer1) and checker (officer2) |
| [wallet.http](wallet.http) | Own wallet, freeze and unfreeze by officers |
| [transfers.http](transfers.http) | P2P transfers, idempotency, high-value hold and officer review |
| [topups.http](topups.http) | Razorpay test-mode top-ups and the webhook signature check |
| [history.http](history.http) | Transaction history: filters, sort, offset and keyset pages |
| [privacy.http](privacy.http) | Data export, erasure, request status |
| [audit.http](audit.http) | Audit events and hash-chain verification (auditor1) |
| [admin.http](admin.http) | Reconciliation run and its metric (admin1) |
| [aml-demo.http](aml-demo.http) | The AML story: structuring top-ups, automatic freeze, case, escalation, STR CSV ([docs/aml-demo.md](../docs/aml-demo.md)) |
| [aml-cases.http](aml-cases.http) | The other case branch (close as false positive), filters, refused transitions |

## Every SPEC §5 endpoint

Base path `/api/v1`. "Not built" endpoints are in the files too, with the answer they give today.

| Method | Path | Role | File |
| --- | --- | --- | --- |
| GET | `/me` | Any | auth.http, demo.http |
| GET, POST | `/me/consents` | CUSTOMER | auth.http |
| POST | `/kyc/min` | CUSTOMER | auth.http |
| POST | `/kyc/full` | CUSTOMER | kyc.http |
| POST | `/kyc/cases/{id}/documents` | CUSTOMER (owner) | kyc.http |
| GET | `/kyc/me` | CUSTOMER | auth.http, kyc.http, demo.http |
| GET | `/compliance/kyc-cases` | COMPLIANCE_OFFICER | kyc.http |
| GET | `/compliance/kyc-cases/{id}` | COMPLIANCE_OFFICER | kyc.http |
| POST | `/compliance/kyc-cases/{id}/verify`, `/approve`, `/reject` | COMPLIANCE_OFFICER | kyc.http |
| GET | `/compliance/kyc-cases/{id}/documents/{docId}` | COMPLIANCE_OFFICER | kyc.http |
| GET | `/wallets/me` | CUSTOMER | wallet.http, demo.http |
| GET | `/wallets/me/transactions` | CUSTOMER | history.http, demo.http |
| GET | `/wallets/me/statements/{yyyy-MM}` | CUSTOMER | history.http (**not built**: 404) |
| POST | `/compliance/wallets/{id}/freeze`, `/unfreeze` | COMPLIANCE_OFFICER | wallet.http |
| POST | `/transfers` | CUSTOMER | transfers.http, demo.http |
| GET | `/transfers/{id}` | CUSTOMER (sender or receiver) | transfers.http, demo.http |
| GET | `/compliance/transfers?status=HELD_FOR_REVIEW` | COMPLIANCE_OFFICER | transfers.http |
| POST | `/compliance/transfers/{id}/approve`, `/reject` | COMPLIANCE_OFFICER | transfers.http |
| POST | `/topups` | CUSTOMER | topups.http, aml-demo.http |
| GET | `/topups/{id}` | CUSTOMER (owner) | topups.http |
| POST | `/webhooks/razorpay` | Public (HMAC) | topups.http (bad signatures); signed ones: `scripts/aml-demo-webhooks.sh capture` |
| POST | `/admin/reconciliation/run` | ADMIN | admin.http |
| GET | `/admin/ledger/accounts/{code}/balance` | ADMIN, AUDITOR | audit.http (**not built**: 404) |
| GET | `/audit/events` | AUDITOR | audit.http |
| GET | `/audit/verify` | AUDITOR | audit.http |
| POST | `/me/data-export` | CUSTOMER | privacy.http |
| POST | `/me/erasure-request` | CUSTOMER | privacy.http |
| GET | `/me/data-requests` | CUSTOMER | privacy.http |
| GET | `/aml/alerts` (port 8081) | COMPLIANCE_OFFICER | aml-cases.http (**not built**: 403) |
| GET | `/aml/cases`, `/aml/cases/{id}` (port 8081) | COMPLIANCE_OFFICER | aml-demo.http, aml-cases.http |
| POST | `/aml/cases/{id}/assign`, `/close`, `/escalate` (port 8081) | COMPLIANCE_OFFICER | aml-demo.http, aml-cases.http |
| GET | `/aml/cases/{id}/str-report` (port 8081) | COMPLIANCE_OFFICER | aml-demo.http |
| POST | `/aml/cases/{id}/ai-summary` (port 8081) | COMPLIANCE_OFFICER | aml-cases.http (**not built**, optional M15: 403) |

Not in §5 but in the collection: `GET /ping` (auth.http; echoes the token's subject and roles) and the actuator
endpoints (`/actuator/health` public, `/actuator/metrics/...` ADMIN).
