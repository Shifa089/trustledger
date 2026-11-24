# Demo video script (5 minutes)

The recording script for the README's demo video (SPEC §11 "5-minute demo video script", M16). It has seven scenes,
each with its timing, what's on screen, what you do, and what to say.

**Local only. Razorpay TEST mode only. Fake users only.** Commands run from `dev/` in Git Bash.

The requests come from the IntelliJ HTTP Client files in [http/](../http/):
- [auth.http](../http/auth.http) (tokens, minimum KYC)
- [kyc.http](../http/kyc.http)
- [topups.http](../http/topups.http)
- [demo.http](../http/demo.http)
- [aml-demo.http](../http/aml-demo.http)
- [aml-cases.http](../http/aml-cases.http)
- [audit.http](../http/audit.http)

> This script was put together from the docs, collections and scripts that already exist. It has **not** been
> rehearsed end to end. Do one dry run before recording.

---

## Before you record (off camera, about 15 minutes)

1. **A clean database, so the KYC scene is real.** The `demo` profile seeds customer1 with FULL KYC, which would
   skip scene 2, so this recording uses the plain `local` profile:
   ```bash
   docker compose -f infra/docker-compose.yml --profile obs down -v
   docker compose -f infra/docker-compose.yml --profile obs up -d
   ./mvnw install -DskipTests
   ./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local          # terminal 1
   ./mvnw -pl risk-aml-service spring-boot:run -Dspring-boot.run.profiles=local  # terminal 2
   ```
2. **customer2 needs FULL KYC as the transfer recipient.** In `auth.http`, get customer2's token and run minimum
   KYC. Then run the `kyc.http` requests with `customer2_token` in place of `customer1_token`. officer1 verifies
   and officer2 approves.
3. **Staff logins need a TOTP code.** Keep a terminal ready with `scripts/aml-demo-webhooks.sh totp` (officer1,
   officer2 and admin1 have TOTP required).
4. **For the real Razorpay checkout in scene 3:** start the tunnel and register the webhook as in
   [e2e-razorpay.md](e2e-razorpay.md), steps 3 and 4. Without a tunnel, use `scripts/aml-demo-webhooks.sh capture`
   to sign and send the webhook yourself (and say so on camera).
5. Open these browser tabs:
   - the README's architecture diagram on GitHub;
   - `http://localhost:8080/checkout.html`;
   - Grafana `http://localhost:3000` (Explore → Tempo);
   - the GitHub Actions run of the last `master` commit;
   - [perf.md](perf.md).
6. Have `psql` ready for scene 6 (user and database from your `.env`):
   ```bash
   set -a; . ./.env; set +a
   docker compose -f infra/docker-compose.yml exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
   ```

---

## Scene 1: the problem and the architecture (0:00–0:30)

**Screen:** README, the Mermaid diagram.

**Say:** "TrustLedger is a regulated digital-wallet backend: KYC tiers, a double-entry ledger, safe transfers, and
AML monitoring in a separate service. The core is a Spring Modulith monolith. Money events leave through a
transactional outbox to Kafka. The AML service reads them, and when it sees something suspicious it asks the core
to freeze the wallet."

## Scene 2: KYC with maker-checker (0:30–1:30)

**Screen:** `auth.http`, `kyc.http`, then `wallet.http`.

**Do:**
1. Get customer1's token, then minimum KYC: a MIN wallet appears.
2. `POST /kyc/full`, then upload the PAN card and the address proof.
3. officer1 (with a TOTP code) calls `verify`.
4. officer1 tries `approve` → **409 `MAKER_CHECKER_VIOLATION`**.
5. officer2 calls `approve`.
6. customer1 runs `GET /wallets/me` → tier **FULL**.

**Say:** "Two different officers have to sign off. The same officer approving their own check is refused by the
domain, and a test proves it (`ComplianceKycIT`)."

## Scene 3: add ₹5,000 through Razorpay test checkout (1:30–2:30)

**Screen:** `topups.http`, then `checkout.html`, then the core-app log, then `GET /wallets/me`.

**Do:**
1. `POST /topups` for 5000.00 with an `Idempotency-Key` → `PENDING`, with a Razorpay order ID.
2. Pay on `checkout.html` with Razorpay's test card.
3. The webhook arrives: the log shows the signature verified and the wallet credited.
4. Show the balance.
5. Show the journal entry: `GET /admin/ledger/accounts/WALLET:<walletId>/balance` as auditor1 (`audit.http`). It
   is summed from the journal and matches the wallet.

**Say:** "The wallet is credited only by the signed webhook, never by the browser. A duplicate delivery is answered
200 and not processed again. The ledger balance comes from the journal lines, not from the wallet's cached
balance."

## Scene 4: idempotency and concurrency (2:30–3:15)

**Screen:** `demo.http`, requests 7 to 9, then a terminal.

**Do:**
1. Send ₹1,500 to customer2 → 201 `COMPLETED`.
2. Send the exact same request again → 201, the **same** transfer ID, header `Idempotent-Replayed: true`, no second
   debit.
3. Same key, different amount → **422 `IDEMPOTENCY_KEY_REUSED`**.
4. Run the concurrency test (start it before the scene; it takes about 1.5 min with container start-up):
   ```bash
   ./mvnw -pl core-app verify -DskipUnitTests -Dit.test=TransferConcurrencyIT -Djacoco.skip=true
   ```

**Say:** "100 transfers at once from a wallet that can afford 50: exactly 50 succeed and the balance ends at zero,
never negative. Then the real reconciliation job runs and finds no mismatch. Wallets are locked with SELECT FOR
UPDATE in ascending ID order, so opposite transfers can't deadlock either."

## Scene 5: AML structuring → freeze → case (3:15–4:00)

**Screen:** `aml-demo.http`, then the AML service log, then `aml-cases.http`. The full walkthrough is
[aml-demo.md](aml-demo.md).

**Do:**
1. Three ₹46,000 top-ups for customer1, captured with `scripts/aml-demo-webhooks.sh capture <id> <id> <id>`.
2. About a second later, customer1's next transfer gets **409 `WALLET_FROZEN`**.
3. officer1 runs `GET /aml/alerts?walletId=…` → one **R2 HIGH** alert.
4. officer1 opens the case, assigns it, escalates it, and downloads the STR CSV.

**Say:** "Three transactions just under the ₹50,000 review threshold within 24 hours: that's structuring, rule R2,
HIGH. The AML service writes the alert and a freeze request in one transaction, and its outbox sends the request to
the core, which freezes the wallet. A redelivered event is processed once. A message that can't be read goes to
the dead-letter topic."

## Scene 6: one trace, and a tamper-evident audit trail (4:00–4:30)

**Screen:** Grafana Tempo, then `audit.http`, then `psql`.

**Do:**
1. In Tempo, open the trace of one of the scene 5 webhooks. It runs core-app → Kafka → risk-aml-service.
2. `GET /audit/verify` as auditor1 → `OK`.
3. In `psql`, play a DBA editing history. The append-only triggers refuse UPDATE, so bypass them for this one
   transaction, the same way `AuditApiIT.asDba` does:
   ```sql
   BEGIN;
   SET LOCAL session_replication_role = replica;
   UPDATE audit.audit_events SET action = 'TAMPERED' WHERE seq = (SELECT max(seq) FROM audit.audit_events);
   COMMIT;
   ```
   (Show first that a plain `UPDATE` without the `SET LOCAL` is refused: that's the append-only guard.)
4. `GET /audit/verify` again → it names the broken `seq`.

**Say:** "Every audited action is chained with SHA-256 to the one before it. Edit one row and verification points
at exactly that row."

> The table and column names (`audit.audit_events`, `seq`, `action`) are from `V19__audit_create_audit_events.sql`.
> The edit permanently breaks the local chain, so do it last, or run `down -v` afterwards.

## Scene 7: tests, CI and numbers (4:30–5:00)

**Screen:** the README "Tests" section, then the GitHub Actions run, then [perf.md](perf.md).

**Say:**
- "About 2,400 tests run against real PostgreSQL, Kafka, Redis and Keycloak in Testcontainers. There's no H2.
  Module boundaries are checked by Spring Modulith and ArchUnit."
- "CI runs the build, Trivy, gitleaks and the image build."
- "Under load: p99 82 ms at 10 transfers a second. The 100 per second target is not met on a laptop, and the README
  says why."

---

## After recording

- Upload the video, then replace the "link coming" line in [README.md](../README.md) section 1 with the URL.
- Run `docker compose -f infra/docker-compose.yml down -v` if you edited the audit table. The next `up` starts clean.
