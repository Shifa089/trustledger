# Changes

## DAY24 Session B (M13): OWASP API Top 10 checklist, security headers, endpoint authorisation coverage, Trivy and gitleaks in CI

**Goal** (SPEC §2 non-functional requirements "Security", §8 M13):
1. `docs/security-checklist.md`: each OWASP API Security Top 10 (2023) item → our control → the test that proves it.
2. A security headers test, and a test that every request mapping has an explicit authorisation rule.
3. CI: Trivy (dependencies, fail on CRITICAL) and gitleaks secret scanning over the whole history.

Rest of M13, still open: Hikari pool sizes and `EXPLAIN ANALYZE` indexes (Session A's hypothesis for the
virtual-threads result).

Branch: `feature/day24b-security` (from `master`, which has Day 24 Session A).

Decisions (approved in the plan):
- **Trivy gate = CRITICAL with a fix (`--ignore-unfixed`).** A CRITICAL without a fixed version can't be solved by
  upgrading, so it would only block CI. It still appears in the uploaded report. Exceptions go in `.trivyignore`,
  each with an expiry.
- **Scan the shipped jars, not the poms.** `trivy rootfs` over the two Spring Boot jars sees the versions in
  `BOOT-INF/lib`, after BOM management and dependency mediation.
- **Both scanners from official images pinned by `@sha256` digest, no third-party actions.** Scanner actions have
  been hit by tag hijacking, and a digest can't be moved.
- **The coverage test also covers risk-aml-service.**
- **ADR 0021** records the tools, the gate and the pinning, even though they are CI tools, not Maven dependencies.
  No new Maven dependency.

---

### 1. `docs/security-checklist.md`

- One section per item, API1:2023 to API10:2023, each with **Risk here**, **Controls**, **Tests** (classes, and the
  methods that matter) and **Gaps**. Every claim was checked against the code. Two were reworded because the code
  didn't support them as first written:
  - offset paging still exists in some lists, so only transaction history is described as using a keyset cursor;
  - how Jackson handles unknown fields wasn't verified, so the checklist describes what the record types guarantee
    instead.
- A closing table covers the supply-chain and secret scans.
- **Gaps written down, not hidden:**
  - API3: no test sends an extra `"status"` field.
  - API4: the load ceiling from Session A, and no global or per-IP limit yet. That belongs to the M14 proxy.
  - API7: no test checks that the gateway client never follows redirects.
  - API8: 6 HIGH findings (see §4).
  - API9: `SecurityConfig` has a rule for `GET /admin/ledger/accounts/*/balance`, but no controller serves it.
- README: ADR table row and a link to the checklist. CLAUDE.md Stack: the CI-only tools.

### 2. Endpoint authorisation coverage (`EndpointAuthorizationCoverageIT`, `AmlEndpointAuthorizationCoverageIT`)

The endpoints come from Spring MVC (`RequestMappingHandlerMapping`), not from a hand-kept list. Test-only probe
controllers in `test-classes` and framework handlers are left out. Four tests per application:

| Test | What it proves |
| --- | --- |
| `everyEndpointIsListedWithItsCallers` | The discovered `METHOD /path` set equals the `EXPECTED` table (32 in core-app, 6 in the AML service). A new endpoint fails until someone decides who may call it; the message says to add the SecurityConfig rule, the matrix row and the table row. |
| `urlRulesAllowExactlyTheExpectedCallers` | For ANONYMOUS, NO_ROLES and the 4 roles, the URL rules grant exactly the expected callers. An endpoint that nobody may call is reported as "no rule of its own: every caller falls through to denyAll()". |
| `preAuthorizeGrantsTheSameRolesAsTheUrlRules` | Every handler has `@PreAuthorize` on the method or class, and it grants the same roles. The only exception is the public Razorpay webhook. |
| `anUnlistedPathIsDeniedForEveryCaller` | The fail-closed default. |

- **How the URL rules are asked:**
  - The test finds the `SecurityFilterChain` that serves the request (via `FilterChainProxy`), so the test-probe
    chain and the local Swagger chain are honoured.
  - It then calls that chain's `AuthorizationFilter.getAuthorizationManager().authorize(...)` with a
    `MockHttpServletRequest`.
  - Spring's `WebInvocationPrivilegeEvaluator` was tried first and failed: its stand-in request throws on
    `getDispatcherType()`, which our `dispatcherTypeMatchers(ERROR)` rule reads.
- **Callers:** Keycloak-shaped JWTs run through the production `JwtRoleConverter`, so the role mapping is the real
  one.
- **It found two real gaps, fixed in this session:**
  - `PingController` had no `@PreAuthorize`. It now has `isAuthenticated()`, like `MeController`.
  - `AmlCaseController` had none. It now has a class-level `hasRole('COMPLIANCE_OFFICER')`.

  The URL rules already restricted both, so neither was exploitable. The fix restores the second layer that
  CLAUDE.md asks for.
- **Mutation check:** removing the `escalate` rule from the AML `SecurityConfig` failed the new test with
  `POST /api/v1/aml/cases/{id}/escalate has no rule of its own...` (and `AmlSecurityIT` with 403 ≠ 404). The file
  was restored.

### 3. Security headers (`SecurityHeadersIT`)

- Takes over the two header tests from `SecurityMatrixIT` (`securityHeadersAreSet` and
  `errorResponsesCarrySecurityHeadersToo` were removed there).
- Parameterised over every kind of response:
  - 200
  - 401 (no token)
  - 403 from a role rule, and 403 from `denyAll()`
  - 400 and 404 ProblemDetails from controllers
  - the public webhook (400)
  - `/actuator/health`
- Each case asserts the full set:
  - CSP equal to `SecurityConfig.API_CSP`;
  - `X-Frame-Options: DENY`, `nosniff` and `Referrer-Policy: no-referrer`;
  - `Cache-Control` containing `no-store`;
  - no `Set-Cookie`;
  - HSTS over HTTPS.
- `hstsIsSentOnlyOverHttps`: no HSTS over plain HTTP.

### 4. CI (`../.github/workflows/ci.yml`): two new jobs, running in parallel with `verify`

**`dependency-scan`**
- **Steps:**
  1. `./mvnw package -DskipTests`.
  2. Copy both Spring Boot jars to `$RUNNER_TEMP/scan`.
  3. Restore the cached Trivy DBs (`actions/cache@v6`).
  4. Report HIGH,CRITICAL to `trivy-report.txt` (exit 0, uploaded as an artifact).
  5. Gate: `--severity CRITICAL --ignore-unfixed --exit-code 1`, with `--skip-db-update`/`--skip-java-db-update`
     since step 4 just downloaded them.
- **Image:** `aquasec/trivy:0.76.2@sha256:8f6bb947…768fb`.
- **`dev/.trivyignore`:** empty, with the rules for an entry (reason, owner, `exp:` date).

**`secret-scan`**
- **Steps:** `actions/checkout` with `fetch-depth: 0`, then
  `gitleaks git /repo --config /repo/.gitleaks.toml --log-opts=--all --redact --exit-code 1`, from the git root.
- **Image:** `ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0…abbb7f`. The image sets `safe.directory=*`, so
  the bind-mounted checkout isn't refused as "dubious ownership".
- **`/.gitleaks.toml`** (git root) extends the default rules with one allowlist:
  - rule `generic-api-key`, AND path `^dev/http/[^/]+\.http$`, AND a line matching `Idempotency-Key: <UUID>`;
  - git mode matches against diff lines, which start with `+`, so the line regex isn't anchored with `^`. The
    first try with `^` still reported all 7 findings.

**Local runs before committing:**

| Check | Result |
| --- | --- |
| gitleaks, whole history (52 commits, 3.5 MB), default rules | 7 findings, all `Idempotency-Key` UUIDs in `http/topups.http` and `http/transfers.http`. These are fake request IDs, not secrets. |
| gitleaks with `.gitleaks.toml` | no leaks found |
| Same header in a non-`.http` file | still reported, so the allowlist is narrow |
| Trivy gate on the two jars | exit 0, 0 CRITICAL |
| Trivy report, HIGH | 6: `lz4-java` 1.11.2 (CVE-2026-106451, fixed 1.11.4) and `snappy-java` 1.1.10.7 (CVE-2026-108106, CVE-2026-93451, fixed 1.1.10.9), in both jars, through kafka-clients |
| Trivy gate on `log4j-core` 2.14.1 (Log4Shell) | exit 1, 2 CRITICAL, so the gate really fails |

**Not done, needs your decision:** override `lz4-java` and `snappy-java` to the fixed versions in the parent POM.
That's a dependency change, so it needs your approval. It should then be checked against the Kafka
integration tests, since both are compression codecs.

### 5. File-based guards (unit tests)

- **`CiSecurityScanTest`** (8), which reads `../../.github/workflows/ci.yml`:
  - the three jobs exist;
  - the Trivy gate flags are present (and `--exit-code 0` is not);
  - both jars are scanned;
  - gitleaks uses `fetch-depth: 0`, `--log-opts=--all`, `--redact` and `--exit-code 1`;
  - `TRIVY_IMAGE` and `GITLEAKS_IMAGE` are pinned by `@sha256` digest;
  - every `uses:` is from `actions/`;
  - `.gitleaks.toml` keeps `useDefault = true`;
  - every `.trivyignore` entry has an `exp:` date.
- **`SecurityChecklistTest`** (3):
  - all ten headings are present, each with Risk, Controls, Tests and Gaps;
  - every item cites a test;
  - every cited test class exists in one of the three modules, and every method named next to it exists
    (`void name(`).

### 6. ADR 0021: `docs/adr/0021-ci-dependency-and-secret-scanning.md`

- Context: vulnerable transitive libraries and secrets in history.
- Decision: the gate, the jar scan, the narrow allowlist and digest pinning.
- Alternatives: the marketplace actions, OWASP Dependency-Check, failing on HIGH, GitHub secret scanning.
- Consequences: how to bump a digest, and how to run both scans locally.

### 7. Verification status

`./mvnw verify` **passed** on this branch, the whole reactor (BUILD SUCCESS):

| Module | Unit tests | Integration tests |
| --- | --- | --- |
| core-app | 1323 (+11: `CiSecurityScanTest` 8, `SecurityChecklistTest` 3) | 812 (+11: coverage 4, headers 9, minus the 2 moved out of `SecurityMatrixIT`) |
| risk-aml-service | 107 | 62 (+4: `AmlEndpointAuthorizationCoverageIT`) |
| event-contracts | 36 | |

`ModularityTests` and `ArchitectureRulesTest` are green. The CI jobs themselves run for the first time on push;
the local runs in §4 used the same images, digests and flags.

### What to learn from it

- **Generate the inventory from the running application, not from memory.** A table someone keeps by hand drifts.
  `RequestMappingHandlerMapping` lists what Spring will actually route, so a forgotten endpoint can't hide. This is
  OWASP API9 turned into a test.
- **Test what the rule does, not whether a rule exists.** "Has a `@PreAuthorize`" is a weak check. Asking the real
  `AuthorizationManager` "would ADMIN get in?" for each caller proves the effective decision, including the order
  of the matchers and the `denyAll()` fallback.
- **Defence in depth has to be tested as two layers.** The URL rules and `@PreAuthorize` should agree. The test
  found two controllers with only one layer. They weren't exploitable today, but one refactor of a path away from
  being open.
- **Prove a gate fails before you trust it.** A scan that is green might be broken. Scanning Log4Shell
  (exit 1) and removing a security rule (the test fails) showed both checks can actually go red.
- **Secrets live in history, not in the working tree.** Deleting a key in the next commit doesn't remove it. That
  is why gitleaks scans `--log-opts=--all` with a full checkout, and why a real finding means *rotate*, never
  *allowlist*.
- **Allowlist the narrowest thing that is true.** Not "ignore `http/`", but one rule, one header, a UUID value,
  those files. The same header anywhere else is still reported.
- **Your scanners are supply chain too.** Pin by digest and avoid third-party actions, or the tool that checks your
  dependencies becomes the easiest dependency to compromise.
- **A checklist is only useful if it can fail.** `SecurityChecklistTest` turns the document into something CI
  checks: rename a cited test and the build tells you the evidence moved.
