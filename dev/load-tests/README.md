# Load tests (M13)

`transfers.js` drives `POST /api/v1/transfers` between 200 seeded FULL-tier wallets with [k6](https://k6.io).
Results go into `docs/perf.md`, with measured numbers only. LOCAL TESTING ONLY, fake users only.

## What is seeded, and how tokens work

| Piece | Where | What |
| --- | --- | --- |
| Wallets | core-app, Spring profile `perf` (`PerfSeeder`) | At startup: users, approved MIN+FULL KYC, a FULL ACTIVE wallet with ₹1,00,000, its ledger account and an opening journal entry, for n = 1..200. Idempotent: a restart creates nothing and keeps balances. |
| Keycloak users | `keycloak-perf-users.sh` | `perf001`..`perf200` (CUSTOMER), each with a fixed ID = the seeded `keycloak_subject`. Client `trustledger-perf` with 30-minute access tokens. |
| Tokens | k6 `setup()` | One password grant per user, once, before the load. All VUs share them. |

The token's `sub` is the seeded subject, so JIT provisioning finds the seeded user, and the user's wallet is the seeded
one. Every request carries a real RS256 token that the app verifies as in production. Keycloak isn't called during the
load. The perf profile also raises `trustledger.rate-limit.money-per-minute` to 600. The limiter stays on, but at
100 req/s over 200 users each user sends about 30 transfers a minute, more than the production budget of 20.

## Run it

Once:

1. Install k6 natively (Windows: `winget install k6 --source winget`). Don't use the Docker image: inside a
   container Keycloak would issue tokens for `host.docker.internal`, and the app accepts only `localhost`.
2. Add `TRUSTLEDGER_PERF_USER_PASSWORD=...` to `.env` (see `.env.example`).
3. Start the infrastructure: `docker compose -f infra/docker-compose.yml up -d`
4. Create the Keycloak users and client (from `dev/`, Git Bash): `load-tests/keycloak-perf-users.sh`

Every run:

1. Start core-app with the perf profile. **Don't start risk-aml-service.** Rule R4 (10+ distinct recipients in 24 h)
   would freeze every wallet within seconds, and the test would measure 409s.
   ```
   ./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local,perf
   ```
   The log shows `Perf seed: 200 of 200 wallets created` (or `0 of 200` on later starts).
2. In another terminal (Git Bash, from `dev/`):
   ```
   k6 run -e PERF_PASSWORD="$(grep ^TRUSTLEDGER_PERF_USER_PASSWORD= .env | cut -d= -f2-)" load-tests/transfers.js
   ```
   Three stages run one after another: 10, 50 and 100 req/s, 2 minutes each, with a 10 s gap. That is about 6.5
   minutes. The run passes when `p(99) < 300 ms` and errors `< 1%` overall and in each stage.

### Virtual threads on vs off (100 req/s)

The app uses virtual threads by default (`spring.threads.virtual.enabled: true`). Run each mode once at a steady
100 req/s. Restart the app in between:

```
# on (default)
./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local,perf
k6 run -e PERF_PASSWORD=... -e MODE=steady load-tests/transfers.js

# off
./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local,perf -Dspring-boot.run.arguments=--spring.threads.virtual.enabled=false
k6 run -e PERF_PASSWORD=... -e MODE=steady load-tests/transfers.js
```

`-e RATE=150` (steady mode only) pushes past the target to find where it breaks.

## Notes

- Amounts are random, ₹1.00 to ₹100.00. A full ramp run is about 96 transfers per wallet (around ₹4,800). That stays
  far below the ₹1,00,000 daily limit and never reaches the ₹50,000 hold. Each run uses new `Idempotency-Key`s, so
  every request moves money.
- Balances drift between wallets but never leave the system. `POST /api/v1/admin/reconciliation/run` (ADMIN) should find
  no mismatches after a run.
- Seeded users have no e-mail in `identity.users`, so transfer notifications are recorded as SKIPPED (after commit,
  off the request path).
