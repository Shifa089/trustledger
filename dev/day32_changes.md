# Changes

## DAY32 (F4): compliance console, admin and auditor

**Goal:** the staff side of the product (build plan "Frontend and live demo", Day 32). Requirements: SPEC FR-KYC-3,
FR-P-3, FR-W-3, FR-AML-1..4, FR-A-1/2, and the compliance, AML, admin and audit endpoints in §5.

Branch: `feature/day32-frontend-f4` (from `master` after the Day 31 merge). Mostly frontend, plus one small, approved
backend endpoint (option B in the plan).

### Decisions

- **One new backend endpoint: `GET /api/v1/compliance/wallets/{id}`** (COMPLIANCE_OFFICER).
  - Without it the console could not show a wallet's real status: only freeze and unfreeze existed, and they return
    the wallet only *after* acting. The build plan's check "the AML freeze shows up in the compliance console" needs
    a read.
  - It returns the existing `ComplianceWalletView` (status, balances, the open freeze with source COMPLIANCE or AML).
    `WalletComplianceService.view` is `@Transactional(readOnly = true)` and takes no row lock: it only displays.
  - Deny by default still holds: the class-level `@PreAuthorize` and `/api/*/compliance/**` rule cover it, and it has
    its own `SecurityMatrixIT` rows (401/403/404/403/403) and an `EndpointAuthorizationCoverageIT` entry.
    An unknown ID is 404 `NOT_FOUND`.
- **Maker-checker is explained, not hidden.**
  - The review page compares `verifiedBy` with the caller's core user ID from `GET /me`. When they match it says "You
    verified this case: a different officer must approve it".
  - The Approve button stays enabled on purpose. The server is the authority. Its 409 `MAKER_CHECKER_VIOLATION`
    gets its own alert, "Maker-checker: approval refused", with the reference, and the case is reloaded unchanged.
  - The three places it's enforced: this UI hint, `KycCase`/`KycReviewService` (409), and the database check
    `ck_kyc_cases_maker_checker` (`approved_by <> verified_by`, V3 migration).
- **Two kinds of officer ID.** core-app records officers by `users.id` (from `/me`). The AML service records
  `assignedTo` as the Keycloak subject (`profile.sub` from the OIDC user). The code never compares one with the other.
- **Document viewer.**
  - Each "View" and "Get a new link" calls the presigned-URL endpoint again. The query has `staleTime: 0, gcTime: 0`,
    because the link expires after 5 minutes and every request is an audited `KYC_DOCUMENT_VIEW`.
  - Images are shown inline. PDFs open in a new tab (`rel="noopener noreferrer"`), because the S3 object is served as
    an attachment. The expiry time is shown.
  - Only `http:`/`https:` URLs are rendered (`lib/staff.ts isSafeUrl`).
- **Every staff decision is guarded against double submits.** These are verify, approve, reject, approve or reject
  of a held transfer, freeze, unfreeze, assign, close, escalate, run reconciliation and verify chain.
  - Each has a `useRef` in-flight flag plus a disabled button, the same pattern as Day 31.
  - Held transfers also need a confirm step: "Send ₹75,000.00?" or "Release ₹75,000.00?".
- **Reasons and notes mirror the backend.** One `ReasonForm` (React Hook Form + Zod) is used for KYC reject (≤ 500,
  `KycCase.MAX_REASON_LENGTH`), freeze and unfreeze (≤ 500, `Wallet.MAX_REASON_LENGTH`) and AML close and escalate
  (≤ 2,000, `AmlCase.MAX_NOTES_LENGTH`). Values are trimmed, and an empty value says "Enter a reason."
- **AML case workflow in the UI.**
  - OPEN offers "Assign to me".
  - INVESTIGATING by someone else offers "Take over" (the backend allows reassignment while active).
  - INVESTIGATING by me offers "Close as false positive…" (sends `resolution: FALSE_POSITIVE` and notes) and
    "Escalate (STR)…".
  - ESCALATED offers "Download STR (CSV)". It goes through `amlApi.download` and a temporary object URL, with the
    server's file name (the AML service exposes `Content-Disposition`).
  - 409s (`NOT_CASE_ASSIGNEE`, `INVALID_STATE_TRANSITION`) use the existing friendly messages, and the case is
    reloaded.
- **The case timeline** (`features/aml/timeline.ts`) merges facts, alerts and freeze requests (requested and sent to
  core-app) in time order. When entries share an instant, the order is the fact, then its alert, then the freeze.
  The case page also shows the wallet's live core-app status: "Frozen by the AML service on …".
- **Filters live in the URL** and are validated before sending, the same idea as Day 30's history. Unknown values are
  dropped and bad IDs are reported, so a hand-edited URL never causes a 400.
  - KYC queue: status tabs.
  - AML alerts: rule, severity, wallet.
  - AML cases: status, open by default.
  - Audit log: entity type, entity ID, actor ID, action, from/to.
  - The audit API filters by `Instant` (`from` inclusive, `to` exclusive). The India-time days become
    `startOfIndianDay(from)` and `startOfIndianDay(to + 1 day)` (new in `lib/dates.ts`).
- **Verify chain is a mutation, not a query.** It walks the whole chain, so it runs only when the auditor presses the
  button.
  - OK shows "N events checked, up to sequence #X".
  - BROKEN shows "Chain broken at sequence #N" and what the reason means (`PREV_HASH_MISMATCH`, `HASH_MISMATCH`,
    `UNREADABLE`).
- **Reconciliation result** shows "All balances match" or "Differences found", the three counts and the duration,
  plus a mismatch table. A negative ledger balance is shown as "−₹12.50", and a truncated list is called out.
- **Section homes** became real dashboards. Compliance shows counts for KYC to verify, KYC to approve, held transfers
  and open AML cases. Admin and Audit link to their screens. The "Coming next" cards are gone.
- **No new dependencies.** TOTP for the e2e staff logins uses Node's `crypto` (RFC 6238, HmacSHA1, 30 s, as in the
  realm export).

### Files

**Backend** (`core-app/`)
- `wallet/application/WalletComplianceService.java`: `view(walletId)`, read-only.
- `wallet/web/ComplianceWalletController.java`: `GET /compliance/wallets/{id}`.
- Tests:
  - `WalletControllerIT`: an officer reads a wallet before and after a freeze. An unknown ID is 404, a customer is 403.
  - `SecurityMatrixIT`: 5 rows.
  - `EndpointAuthorizationCoverageIT`: 1 entry.
- `docs/SPEC.md` §5 and `http/wallet.http`: the new endpoint.

**Frontend** (`frontend/`)
- `src/api/`:
  - `compliance.ts` (new): KYC queue, case, document link and decisions; held transfers; the compliance wallet read,
    freeze and unfreeze.
  - `aml.ts` (new): alerts, cases, case actions, STR download.
  - `operations.ts` (new): reconciliation, audit events, verify.
  - `me.ts` (new): `useMe`.
- `src/features/compliance/` (new): `KycQueuePage`, `KycReviewPage`, `DocumentViewer`, `HeldTransfersPage`,
  `WalletPage`.
- `src/features/aml/` (new): `AmlAlertsPage`, `AmlCasesPage`, `AmlCasePage`, `timeline.ts`, `filters.ts`.
- `src/features/admin/ReconciliationPage.tsx` (new).
- `src/features/audit/` (new): `AuditEventsPage`, `AuditVerify`, `auditQuery.ts`.
- `src/components/staff.tsx` (new): `PageHeader`, `Pager`, `StatusBadge`, `Id`, `ReasonForm`, table helpers.
  `src/lib/staff.ts` (new): `pageFrom`, `isSafeUrl`, `saveBlob`.
- `src/lib/dates.ts`: `startOfIndianDay`.
- `src/router.tsx`: 11 staff routes under their `RequireRole` blocks. `src/nav.ts`: the staff items are links now,
  plus "Wallets", "AML alerts" and "AML cases".
- `src/pages/`: `SectionHome` takes link cards. `ComplianceHome` shows queue counts. `AdminHome` and `AuditHome`
  link to their screens.
- `src/test/server.ts`: default empty handlers for the staff lists.
- `e2e/stack.ts` (new): staff login with TOTP, a fresh customer through minimum and full KYC with in-memory fake PNGs,
  `approveKyc`, a signed TEST-mode `payment.captured` webhook (localhost only), and a stub for Razorpay's hosted
  Checkout script.
- `e2e/compliance.spec.ts` and `e2e/aml.spec.ts` (new, `@stack`). `e2e/login.spec.ts`: the auditor's "Audit" link
  is matched exactly now that "Audit log" exists.

**Docs**
- README "Web app": what staff can do, and the extra variables the staff e2e specs need.

### Tests

- `KycReview.test.tsx` (7):
  - **The verifier presses Approve and gets the maker-checker 409 alert**: the hint first, then the server is
    called once, and the case stays Verified.
  - Verify works. A different officer approves. Reject needs a reason and sends exactly `{reason}`.
  - The document viewer: fresh presigned link per view (3 calls for 3 views), image inline, PDF in a new tab with
    `noopener noreferrer`, the expiry shown, and a `javascript:` URL is never rendered.
  - The queue: status tabs in the URL, and "verified by you".
- `AmlCasePage.test.tsx` (7):
  - The timeline order (unit).
  - Alerts, the timeline and the AML-frozen wallet status.
  - Assign to me sends `{}`.
  - Close requires notes and sends `FALSE_POSITIVE`.
  - Escalate, then the STR CSV is saved with the server's file name.
  - 409 `NOT_CASE_ASSIGNEE` is explained.
  - "Take over" is offered without close or escalate when another officer holds the case.
- `AuditVerify.test.tsx` (3): runs only when asked; OK with the count; BROKEN at #42 with the reason; an error, then
  a retry.
- `StaffActions.test.tsx` (8):
  - Held transfers: confirm, then approve; **a double click sends one approve**; a 409 keeps the row.
  - Wallet: look up by ID (non-UUIDs refused); freeze needs a reason, then Unfreeze is offered; an AML freeze is
    shown; 404.
  - Reconciliation: **a double click runs once**; all match; mismatches with a negative and an unlinked row and the
    truncated notice.
- `auditQuery.test.ts` (3): India days to instants (`to` exclusive), bad IDs and a reversed range reported, alert
  filters sanitised.
- `router.test.tsx` (+17):
  - **Typed staff URLs are refused** for the wrong role: a customer on compliance pages, an admin on AML, an officer
    on audit, and so on.
  - Each staff screen is in its role's navigation and opens.
- Playwright `@stack`:
  - `compliance.spec.ts`:
    - A fresh customer submits full KYC with three fake documents.
    - officer1 finds the case in the queue, views the PAN image through a presigned link, and verifies.
    - officer1 then presses Approve and sees the maker-checker refusal.
    - officer2 approves, and the customer's dashboard shows **Full KYC**.
    - Then typed URLs: a customer on `/compliance/kyc`, an officer on `/customer/send` and `/audit/events` all land
      on `/forbidden`.
  - `aml.spec.ts`:
    - A fresh customer goes through KYC with officer1 and officer2.
    - They make **three ₹46,000 top-ups** on the real Add money screen. Checkout is stubbed, and each payment is
      captured by a signed TEST webhook.
    - The wallet freezes. The **customer's dashboard says "Wallet frozen"**, without the rule.
    - officer1 filters alerts by R2, HIGH and the wallet, and opens the case: the timeline has the top-ups and
      "Freeze requested (R2 Structuring)", and the status shows "Frozen by the AML service".
    - The wallet page shows **Frozen, "AML service (automatic)"**.

### Verification

- `npm run ci`: lint (0 warnings), type-check, **306 Vitest tests in 24 files** and the build all pass. Day 31 had
  261 in 19 files; this adds 28 in the five new files and 17 router rows. The build prints Vite's chunk-size
  advisory (a warning, not an error). Splitting the staff screens into lazy routes would remove it; not done today.
- `npm run e2e` (no backend): 3 passed.
- `npm run e2e:stack` against the local stack (core-app `local,demo`, the AML service `local`, Keycloak), with the
  secrets from `dev/.env`: **all 6 specs pass in one run** (aml, compliance, 2 × login, onboarding, transfer).
  - In the first full run, `login.spec.ts` failed because its "Audit" link match now also hit "Audit log" (fixed
    with `exact: true`).
  - In that run, Day 31's `transfer.spec.ts` history check also failed once. It passed in every later run (2
    repeats, then the full suite). This looks like the same unexplained flake recorded on Day 31.
  - aml.spec found a real timing detail: the AML freeze landed about 0.4 s after the third webhook, so the Add money
    page can show "Wallet frozen" instead of the third "added" message. The spec accepts either on the last top-up.
- `GET /compliance/wallets/{id}`: `WalletControllerIT` (15), `SecurityMatrixIT` (213), `EndpointAuthorizationCoverageIT`,
  `ArchitectureRulesTest` and `ModularityTests` pass.
- `./mvnw verify`: **2,464 tests, 0 failures in the end, but not in one clean run.**
  - Per module: event-contracts 36; core-app 1,372 unit and 838 integration; risk-aml-service 146 unit and 72
    integration. core-app's integration count is +7 over Day 31: 2 `WalletControllerIT` tests and 5
    `SecurityMatrixIT` rows.
  - Run 1, with Vitest running on the same CPU: `RazorpayGatewayAdapterTest.timeoutsAloneOpenTheCircuitWhichThenFailsFastWith503`,
    a timing-based circuit-breaker unit test in payments, failed. It passed in run 2 with nothing else running.
  - Run 2: all 1,372 unit tests passed. `ActuatorEndpointsIT` and `RateLimitIT` each got a 503 from
    `/actuator/health`, because the Mailpit Testcontainer's SMTP health check timed out ("Exception reading
    response"). Rerun alone, both passed (4/4 and 7/7).
  - The reactor stops at the first failing module, so I ran `./mvnw -pl risk-aml-service verify` separately: BUILD
    SUCCESS.
  - None of these tests touch today's code. All three failures are timing or environment flakes on a loaded laptop,
    but CI should be watched for them.

### Not done / noted

- **One load-related unit-test failure.** Running the new test files at the same moment as `./mvnw verify`'s
  compile and Testcontainers start, 3 tests failed once. Four reruns, also under load, passed 65 of 65. The likely
  cause is Testing Library's 1 s `findBy` default on a starved CPU. I didn't raise the global timeout. If it shows up
  in CI, raise `asyncUtilTimeout` in `src/test/setup.ts` rather than per test.
- **The AML service has no wallet read of its own.** The case page asks core-app (`GET /compliance/wallets/{id}`) for
  the live status. If core-app is down, the case still loads and only that card shows an error.
- **No officer names.** Officers appear as short IDs, or "you". Neither service exposes a user directory, and adding
  one is a backend change for another day.
- **Audit before/after is shown as masked JSON**, exactly as the API returns it. The UI never unmasks.
- **CSP** for the deployed app is still Day 33. The staff screens need no new origins: the presigned S3 URLs come
  from the existing storage origin, and Day 33's `img-src` must allow it for the document viewer.

### What to learn from Day 32

- **Maker-checker in three places.**
  - The **UI hint**, for convenience: "You verified this case".
  - The **service** rule, the authority: 409 `MAKER_CHECKER_VIOLATION` from `KycReviewService`.
  - The **database** check `ck_kyc_cases_maker_checker`: the last line if a bug or a direct SQL write ever got past
    the service.
  - The UI deliberately does *not* disable Approve. A hidden rule can't be tested from the browser, and a
    stale or tampered client must still meet the server's refusal.
- **Different services, different identities.** core-app knows people by `users.id`, and the AML service knows
  officers by their Keycloak `sub`. Comparing the wrong pair would silently break "is this assigned to me". Make the
  identity source explicit at each call site.
- **Read endpoints matter for compliance UX.** Every write in the console was already there. What was missing was
  the read that shows the *current* state before acting. That is why the AML freeze couldn't be seen until
  `GET /compliance/wallets/{id}` existed.
