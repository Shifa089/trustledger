# Changes

## DAY26 Session B: closing the regression gaps

**Goal:** fix what the Day 26 A audit found failing or partial, except `LEARNING.md` (you'll write it yourself).
1. Swagger UI on `risk-aml-service` (DoD **fail**).
2. Build the two missing endpoints that aren't on the cut list:
   - `GET /aml/alerts`;
   - `GET /admin/ledger/accounts/{code}/balance`.
3. README demo video (**partial**): write `docs/demo-script.md`.
4. Close the small gaps between test wording and SPEC wording:
   - must-pass #4 (reconciliation);
   - must-pass #9 (timeouts alone open the circuit);
   - the monthly-limit test that skipped on the 1st.
5. Docs: `http/`, README, ADRs, security checklist.

Not in scope: `LEARNING.md`, and the 4 deferred features (PDF statements, AI case summary, AWS, Envers).

Branch: `feature/day26b-gaps` (from `master`, which has Day 26 Session A).

Decisions:
- **No new dependency.** The AML service gets `springdoc-openapi-starter-webmvc-ui`, the library ADR 0010 already
  approved, with the version managed in the parent POM. ADR 0010 gets a dated note instead of a new ADR.
- **Swagger on the AML service has the same rules as core-app.** In the `local` profile it needs no token. Everywhere
  else it needs a signed-in caller. It has a separate `@Order(1)` chain, because the UI needs scripts and styles that
  the strict API CSP forbids.
- **The alert list is newest first**, while the case list is oldest first. Cases are a work queue, so the oldest goes
  first. Alerts are a feed, read from the top.
- **The alert search is a Spring Data fragment** (`AlertSearch` / `AlertSearchImpl`, Criteria API), not
  `JpaSpecificationExecutor`. That interface would also add `delete(Specification)` to an append-only table. Only
  the filters that are set become predicates, which avoids the `:param IS NULL` trick (PostgreSQL can't type a
  null UUID).
- **The ledger balance is signed.** A platform account such as suspense can legitimately be below zero. The amount
  is a decimal string built with `BigDecimal.valueOf(minor, 2)`, with no floating point.
- **A malformed account code is a 400 and an unknown one a 404.** The code is checked against the shape the database
  allows before any query runs.
- **The test probe for the ledger balance path is gone.** `SecurityMatrixIT` now calls the real controller.
- No migration. `ix_alerts_wallet_created` already serves the wallet filter.

---

### 1. Swagger UI on `risk-aml-service` (DoD fail → pass)

| File | What |
| --- | --- |
| `risk-aml-service/pom.xml` | `springdoc-openapi-starter-webmvc-ui` (version from the parent POM) |
| `shared/config/OpenApiConfig.java` | Title "TrustLedger Risk and AML API", the `bearerAuth` JWT scheme, copied from core-app |
| `shared/security/AmlSecurityProperties.java` | `trustledger.security.swagger-public`, a `@ConfigurationProperties` record |
| `shared/security/SecurityConfig.java` | `swaggerSecurityFilterChain` (`@Order(1)`, only when `swagger-public=true`). In the main chain the Swagger paths are `authenticated()`, placed just before `denyAll()` |
| `application.yml` / `application-local.yml` | `swagger-public: false` / `true` |

The UI is at http://localhost:8081/swagger-ui.html and lists every AML endpoint.

### 2. `GET /api/v1/aml/alerts` (COMPLIANCE_OFFICER)

Query parameters, all optional:
- `ruleCode`: `R` plus one or two digits, otherwise 400. A well-formed code that no rule uses matches nothing.
- `severity`: `MEDIUM` or `HIGH`.
- `walletId`.
- `page` and `size`: `size` is capped by `trustledger.aml.cases.max-page-size`.

| File | What |
| --- | --- |
| `cases/web/AmlAlertController.java` | `@PreAuthorize("hasRole('COMPLIANCE_OFFICER')")` |
| `cases/application/AmlAlertService.java` | Validation, then `@Transactional(readOnly = true)` search |
| `cases/application/CaseViews.java` | `AlertSummary.of(Alert)` (id, case, wallet, rule, severity, details, time) and `AlertPage` |
| `cases/domain/AlertFilter.java`, `AlertRepository.search(...)` | The port |
| `cases/infrastructure/AlertSearch.java`, `AlertSearchImpl.java` | Criteria query, sorted `createdAt desc, id desc`, so a page boundary is stable |
| `shared/security/SecurityConfig.java` | Its own `GET /api/*/aml/alerts` rule. A POST to the path is refused |

`details` holds only the rule's evidence (amounts, counts, window) and no personal data.

### 3. `GET /api/v1/admin/ledger/accounts/{code}/balance` (ADMIN, AUDITOR)

| File | What |
| --- | --- |
| `ledger/web/LedgerAccountController.java` | `@PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")`, on top of the existing URL rule |
| `ledger/web/LedgerAccountBalanceResponse.java` | `accountCode`, `normalSide`, `balance` (signed decimal string), `currency`, `asOf` |
| `ledger/application/LedgerBalanceService.java` | Checks the code's shape (`PLATFORM:SUSPENSE`, `WALLET:<id>`, at most 64 characters), then `LedgerAccountService.balanceOf`. `asOf` comes from the injected `Clock` |

The balance is always summed from the journal lines and never cached (ADR 0019), so it's the same number
reconciliation compares against. The ledger module still depends on no other business module.

### 4. Test hardening (must-pass wording)

- **#6, monthly limit:** `TransferServiceIT.monthlyLimitIsRefused` used `assumeThat(dayOfMonth > 1)`, so it skipped
  on the 1st. It moved to the new `TransferMonthlyLimitIT`, which has a fixed `Clock` (16 Nov 2026, noon IST) and
  two tests:
  - one paisa over the limit is refused, and exactly the limit is allowed;
  - last month's transfers don't count.
- **#4, reconciliation:** `TransferConcurrencyIT` now runs the real `ReconciliationJob.runNow()` after the 100
  transfers. It checks that neither wallet is in the mismatch list. The test database is shared, so it looks at its
  own wallets only, as `ReconciliationIT` does.
- **#9, circuit breaker. Correction to the Day 26 A audit:** a test where timeouts alone open the circuit already
  existed, `RazorpayGatewayAdapterTest.timeoutsCountAsFailures`. I had only looked at `TopupControllerIT`. It's now
  `timeoutsAloneOpenTheCircuitWhichThenFailsFastWith503` and also checks:
  - each failure is caused by a `TimeoutException`;
  - the open circuit refuses the next call with `CallNotPermittedException` → `GATEWAY_UNAVAILABLE` (503);
  - that refusal is faster than the time limit, and the gateway gets no call.

### 5. Tests

| Test | Kind | What |
| --- | --- | --- |
| `AmlAlertServiceTest` | unit | Filters passed on as given, paging limits, malformed and well-formed rule codes, page totals |
| `AmlSwaggerPublicLocalTest` | unit (MockMvc) | `swagger-public=true`: Swagger needs no token and doesn't use the strict CSP, while the API still needs a token |
| `AmlAlertControllerIT` (5) | Testcontainers | Newest first with the case ID; rule, severity and wallet filters combine; stable pages; bad values → 400 |
| `AmlSecurityIT` (+2) | Testcontainers | Swagger is 401 without a token outside `local`; `/v3/api-docs` lists every AML endpoint and the bearer scheme; alert rows (officer 200, everyone else 403, no token 401, POST 403) |
| `AmlEndpointAuthorizationCoverageIT` | Testcontainers | A row for `GET /api/v1/aml/alerts` |
| `LedgerBalanceServiceTest`, `LedgerAccountBalanceResponseTest` | unit | Code shapes, the 64-character limit, signed two-decimal formatting (`-1250 → "-12.50"`) |
| `LedgerAccountControllerIT` (5) | Testcontainers | A wallet's balance equals its journal lines after a top-up and a transfer; a new account is `"0.00"`; a negative balance is signed; platform accounts show their normal side; unknown → 404, malformed → 400 |
| `SecurityMatrixIT` | Testcontainers | The ledger rows now call the real endpoint with `PLATFORM:SUSPENSE` |
| `EndpointAuthorizationCoverageIT` | Testcontainers | Understands `hasAnyRole(...)`, with a row for the ledger balance |
| `TransferMonthlyLimitIT` (2) | Testcontainers | See §4 |

### 6. Docs

- `docs/demo-script.md` (new): the 5-minute recording script, seven timed scenes following SPEC §11, built from the
  `http/` files. **It hasn't been rehearsed.** Do one dry run before recording.
- `http/aml-cases.http` and `http/audit.http`: the "NOT BUILT YET" entries are now real requests (filters, paging,
  400/403/404 cases).
- `http/README.md`: the endpoint table no longer marks the two endpoints as not built.
- `README.md`:
  - the AML Swagger URL;
  - the demo-script link next to the video placeholder;
  - must-pass rows 4, 6 and 9 name the new or extended tests;
  - the two endpoints are removed from "Not yet built".
- ADR 0007 and ADR 0010: "Update (Day 26 B)" sections.
- `docs/security-checklist.md`: the Day 25 gaps are closed.

### 7. Verification status

**`./mvnw verify` passed, run in two parts.** The first full-reactor attempt was stopped by Claude Code because the
machine ran low on memory. The second attempt failed only because Docker Desktop wasn't running: all 815 core-app
integration tests failed to start their app (Testcontainers: "Could not find a valid Docker environment"). With
Docker running and only about 1.7 GB of RAM free, I ran the same goal one module at a time. That's every test of
every module, though not in one JVM session:
- `./mvnw verify -pl risk-aml-service -am`: BUILD SUCCESS, 1 min 42 s;
- `./mvnw verify -pl core-app -am`: BUILD SUCCESS, 5 min 48 s.

| Module | Unit tests | Integration tests | Failures / errors | Skipped | Lines (JaCoCo) | Branches |
| --- | --- | --- | --- | --- | --- | --- |
| event-contracts | 36 | | 0 | 0 | 100% | 100% |
| core-app | 1,365 (+21) | 822 (+6) | 0 | 0 | 96.6% | 89.1% |
| risk-aml-service | 132 (+20) | 69 (+7) | 0 | 0 | 94.5% | 85.9% |
| **Total** | **1,533** | **891** | **0** | **0** | | |

**2,424 tests (+54 since Day 26 A), 0 failing, 0 skipped.** No `assumeThat` is left in the money paths.
`ModularityTests` and `ArchitectureRulesTest` are green.

**Manual checks** (in the earlier part of this session): both images rebuilt and run with the compose profile
`apps`. `scripts/smoke.sh` passed, and live calls to the new endpoints and to the AML Swagger UI gave the same
answers as the tests. The app containers were stopped afterwards. Before the module builds, the local infrastructure
was stopped too (`docker compose stop`, volumes kept) to free memory. Run `docker compose -f infra/docker-compose.yml
up -d` to bring it back.

### 8. Definition of done (SPEC §12), updated from Day 26 A

| Item | Day 26 A | Now |
| --- | --- | --- |
| `./mvnw verify` passes, including the 16 must-pass tests | Pass | **Pass** (two-part run, see §7; CI runs it whole on push) |
| Clean-machine start | Pass (not re-run) | **Pass (re-run on existing infra).** A truly clean run needs `down -v`, which would delete your local data, so I didn't do it |
| Every endpoint implemented, secured, in `http/` | Partial | **Pass, except the 2 deferred features** (PDF statements, AI summary) |
| Swagger UI documents both services | **Fail** | **Pass** |
| Grafana trace and metrics | Pass | Pass |
| `docs/perf.md` with real numbers | Pass | Pass (100/s target still not met, as stated) |
| Security checklist covers OWASP API Top 10 | Pass | Pass |
| All 9 ADRs written | Pass | Pass (0007 and 0010 updated) |
| README complete with diagram and demo video link | Partial | **Partial:** the script is ready, and the video needs you to record it |
| No secrets in Git history | Pass | Pass (gitleaks in CI) |
| You can explain every module | For you | For you |
| `LEARNING.md` complete | Fail | **Fail, out of scope as you asked** |

All 16 must-pass tests pass, and #4, #6 and #9 now match the SPEC wording exactly.

### Needs your decision

- **Record the demo video** using `docs/demo-script.md`, then give me the URL for the README.
- **`LEARNING.md`** is still empty.
- **Bring the local infrastructure back** when you need it: `docker compose -f infra/docker-compose.yml up -d`.

### What to learn from it

- **Check your own audit before acting on it.** Day 26 A said no test opened the circuit by timeouts alone. One
  did, in the adapter's unit test. Searching for the behaviour (`withFixedDelay` + `State.OPEN`) instead of the class
  you expect would have found it.
- **A fixed `Clock` beats `assumeThat`.** A date-dependent assumption quietly skips a test on some days. Injecting
  a `Clock` (the project rule) makes the test deterministic, and it made a "last month doesn't count" case easy to
  add.
- **An interface brings everything it declares.** `JpaSpecificationExecutor` would have given an append-only table a
  `delete` method. A small custom fragment exposes only the query you need.
- **Signed money is a real case.** A suspense account below zero isn't a bug. The API has to show the sign, and the
  test has to prove it.
- **Test probes can hide missing features.** A test-only controller answered on the ledger balance path, so the
  security matrix was green for an endpoint that didn't exist. Point security tests at real handlers once they exist.
- **Memory is part of the build environment.** Testcontainers needs Docker running and enough RAM free. Splitting
  the reactor with `-pl <module> -am` runs the same tests with a smaller peak, and the notes should say that's what
  happened.
