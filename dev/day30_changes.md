# Changes

## DAY30 (F2): KYC onboarding, wallet dashboard, transaction history

**Goal:** a customer can onboard and see their money (build plan "Frontend and live demo", Day 30). Requirements:
SPEC FR-KYC-1/2, FR-W-1/2, FR-S-1 and their endpoints in §5.

Branch: `feature/day30-frontend-f2` (from `feature/day29-frontend-f1`, which was not merged into `master` yet).

### Decisions

- **"Limits used" got a backend endpoint: `GET /wallets/me/limit-usage`** (reporting module, CUSTOMER).
  - SPEC §5 promised "limits used" on `/wallets/me`, but `MyWalletView` only carried the limits. The wallet module
    can't read payments, because payments already depends on wallet.
  - `reporting` already joins `WalletApi` and `PaymentsApi`. A new `PaymentsApi.limitUsage(walletId)` reuses the
    sums the limit checkers use: `LimitChecker.usage()` and `TopupLimitChecker.loadedThisMonth()`. That means the same
    IST day and month windows and the same statuses. Transfers count when COMPLETED or HELD_FOR_REVIEW. Top-ups count
    when CREATED, PENDING or CAPTURED.
  - So the bars show exactly what the backend will enforce. The rules are not copied into the browser. The read takes
    no lock: it is a snapshot for display, never the basis for allowing a payment.
  - `/api/*/wallets/**` was already CUSTOMER-only in `SecurityConfig`, and the controller repeats
    `@PreAuthorize`. It has SecurityMatrixIT rows, a request in `http/wallet.http`, and a row in SPEC §5.
- **New dependencies:** `zod`, `react-hook-form` and `recharts`, as planned in ADR 0023, plus `@hookform/resolvers`,
  the official package that joins the two (recorded in ADR 0023). No Radix packages: input, label, select and
  progress are small local components on native elements.
- **`formatMoney(paise: bigint | number)`** replaces `formatPaise`. It uses bigint `/ 100n` and `% 100n` only. A
  `number` is accepted only as a safe integer, so 1.5 or 2^53 + 1 throws instead of rounding silently.
  `percentUsed(used, limit)` for the bars is bigint integer division, clamped to 0-100, so 99.9% shows as 99.
- **Days are Indian calendar days** (`src/lib/dates.ts`), because the backend counts limits, history `from`/`to` and
  the minimum age in Asia/Kolkata. A day is a `yyyy-MM-dd` string: strings compare correctly, and midnight never
  shifts with a time zone.
- **The Zod rules mirror the backend validators:**
  - `@ValidPan`, including the 4th-letter holder type, and `@ValidIndianMobile`.
  - 18+ with the same 29 February rule as `AgePolicy` (`LocalDate.plusYears`).
  - Name ≤ 100 and address ≤ 500 characters.
  - The server stays the authority: its `VALIDATION_FAILED` violations are put back on the matching fields.
- **Document upload:**
  - Each file's type (PDF/JPG/PNG, by MIME type and extension) and size (≤ 5 MiB, Spring's "5MB") is checked
    before anything is sent.
  - Upload progress needs `XMLHttpRequest`, so the API client gained `upload()`. It sends the same bearer token and
    correlation ID and maps ProblemDetail errors the same way as every other call.
  - ESLint now bans `XMLHttpRequest` outside `src/api/client.ts`, like `fetch`.
  - After a reload, the required document list is unknown (`GET /kyc/me` has no document data), so every type is
    offered.
- **The wallet appears asynchronously after minimum KYC.** The `KycLevelUpgraded` listener runs after commit. Coming
  from the form, the dashboard polls `/wallets/me` once a second, up to 20 times, while it shows "Setting up your
  wallet…".
- **30-day chart:**
  - It reads the history for the last 30 IST days (size 100, at most 5 pages; beyond that it says so).
  - It sums per day in bigint, counting only money that moved: captured top-ups and completed transfers.
  - Bars are plotted in integer paise (no division). Every label goes through `formatMoney`.
  - Y ticks are generated at whole-rupee steps, after a screenshot showed repeated "₹1, ₹1" labels.
  - The palette is validated with the dataviz validator: blue/orange, CVD ΔE 24.7, contrast ≥ 3:1.
  - A caption sums it up, and "Show as table" offers the same numbers as a table.
- **History state lives in the URL:** `from`, `to`, `type`, `sort`, and `page` (1-based for people).
  - Bad hand-edited values are ignored rather than sent.
  - A changed filter or sort returns to page 1.
  - A `from` after `to` is refused without a request.
  - The previous page stays on screen while the next one loads.
  - The Date and Amount headers are sort buttons with `aria-sort`.
- **A bug found by the e2e test:** after minimum KYC, refetching `/kyc/me` made the min-KYC page redirect to
  `/customer/kyc` before the form could open the dashboard. The mutation now invalidates with `refetchType: 'none'`.
  A unit test now covers it (`MinKycPage` → dashboard).
- **The onboarding e2e registers a fresh user each run** through Keycloak self-registration (on, with default role
  CUSTOMER). It uses random fake mobile and PAN values, so it can be rerun. It uses the `en-IN` locale, because the
  date input's segments are dd/mm/yyyy.

### Files

**Backend**
- `payments/LimitUsage.java` (new public record).
- `PaymentsApi.limitUsage` and `PaymentsApiImpl.limitUsage` (read-only transaction).
- `LimitChecker.usage` and `TopupLimitChecker.loadedThisMonth`.
- `reporting/application/LimitUsageService.java` and `reporting/web/LimitUsageController.java` (new).
- `reporting/package-info.java` and the `MyWalletView` javadoc.
- Tests:
  - `LimitUsageIT` (new): rows placed on the real IST day and month boundaries; which statuses count; another
    customer's rows never show; no wallet gives 403 KYC_REQUIRED.
  - `LimitUsageServiceTest` (new).
  - `LimitCheckerTest` and `TopupLimitCheckerTest` (+2 each).
  - `SecurityMatrixIT` (+5 rows) and `EndpointAuthorizationCoverageIT` (the new endpoint and its role in `EXPECTED`).
- `http/wallet.http` and `http/README.md` (`HttpCollectionTest` requires a request for every endpoint). SPEC §5 row.

**Frontend** (`frontend/`)
- `src/api/`:
  - `client.ts`: `upload()` over XHR, with shared error mapping.
  - `types.ts`: KYC, wallet and history types.
  - `kyc.ts`, `wallet.ts`: query hooks; history paging for the chart.
- `src/lib/`: `money.ts` (`formatMoney`, `toPaise`, `percentUsed`) and `dates.ts` (new).
- `src/components/`: `FormField` (label, hint, error, `aria-describedby`); `ui/input` (Input, Select, Textarea),
  `ui/label`, `ui/progress`.
- `src/features/kyc/`: `schemas`, `documents`, `kycState`, `DemoDataNotice`, `MinKycForm`, `MinKycPage`,
  `FullKycPage`, `DocumentUploads`, `KycStatusPage`.
- `src/features/wallet/`: `WalletDashboard`, `LimitBars`, `limits`, `ActivityChart`, `activity`.
- `src/features/history/`: `TransactionHistory`, `TransactionTable`, `transactions`, `historyParams`.
- `src/router.tsx`: `/customer` (dashboard), `/customer/kyc`, `/customer/kyc/min`, `/customer/kyc/full`,
  `/customer/history`, all under `RequireRole CUSTOMER`. `src/nav.ts`: KYC and History are now links.
  `pages/customer/CustomerHome.tsx` was removed (the dashboard replaces it).
- `eslint.config.js`: `XMLHttpRequest` is restricted.

**Tests**
- Vitest (207 tests, 16 files):
  - `formatMoney.test.ts`: 150050 → "₹1,500.50", 5 → "₹0.05", values beyond 2^53, refused floats.
  - `MinKycForm.test.tsx`:
    - Invalid PAN, invalid mobiles and under-18 each show an error and send nothing.
    - Turning 18 today is accepted.
    - Valid input submits the exact body.
    - The form works with the keyboard only, and every input has a label.
    - Server field errors and `PHONE_ALREADY_REGISTERED` are shown.
    - The page redirects to the dashboard after submitting.
  - `WalletDashboard.test.tsx`: amounts, limit bars (value and value text), recent rows with signed amounts, the
    history queries for "recent" and the chart, paging for the chart, the 30-day sum in paise, and the empty, MIN,
    no-wallet, waiting, frozen and error states.
  - `TransactionHistory.test.tsx`: filters, sort and paging change both the request and the URL; the URL is read
    back; bad URL values are dropped; an inverted range sends no request; loading, empty, filtered-empty and error
    (with retry) states.
  - `FullKycPage.test.tsx`: a 5 MiB + 1 file and a `.gif` are refused with no request; exactly 5 MiB uploads;
    required and optional badges; a server 415 is shown; a submitted case resumes after a reload.
  - Also: `dates.test.ts`, `activity.test.ts` (IST buckets, whole-rupee ticks), `kyc.test.ts` (next step, PAN rule,
    document checks), XHR upload cases in `apiClient.test.ts`, customer routes in `router.test.tsx`.
- Playwright: `onboarding.spec.ts` (`@stack`). A new user registers, finds no wallet, opens KYC, completes minimum KYC
  with the keyboard only, and gets a MIN wallet (₹0.00 of ₹10,000.00), "Start full KYC", and an empty history.

### Verification

- `./mvnw verify`: BUILD SUCCESS, 2,457 tests, 0 failures (event-contracts 36; core-app 1,372 unit + 831 integration;
  risk-aml-service 146 + 72). Two earlier full runs failed first: once on the missing `EndpointAuthorizationCoverageIT`
  row (fixed, see Files), once on `RazorpayGatewayAdapterTest.wrongKeysAreRejectedWithoutLeakingTheSecret`, which hit
  the adapter's time limit on a heavily loaded machine. That test is untouched by this branch and passed 3 of 3 runs
  alone and in the final full run.
- `npm run ci`: lint, type-check, 207 Vitest tests and the build pass. `npm run e2e`: 3 passed.
- `npm run e2e:stack` against the running local stack: onboarding, plus Day 29's two login specs, all passed.
- Screenshots of customer1's dashboard and history were checked by eye. That found the y-axis duplicate labels,
  which are now fixed.

**Docs**
- ADR 0023: `@hookform/resolvers` and the XHR upload rule. CLAUDE.md "Frontend": no XHR outside the client; forms,
  dates and money helpers. README: what customers can do, and Keycloak "Register" for a fresh customer. SPEC §5:
  the `limit-usage` row.

### Not done / noted

- **The e2e ran against a core-app that was already running and predates this branch.** So on screen, "limits used"
  showed "Couldn't load…" and only the balance bar. The new endpoint is covered by `LimitUsageIT` and
  `SecurityMatrixIT`. Restart core-app to see all the bars.
- **`GET /kyc/me` has no document list,** so after a reload the upload step can't show which documents were already
  uploaded or which are required. Uploading again adds another copy (the backend allows 10 per case). A small
  follow-up could add document metadata to the customer's case view.
- **The chart reads at most 500 transactions** (5 pages of 100) and says so when it hits that cap. A daily-totals
  endpoint would scale better if a wallet ever has more.
- **The bars are thin** (30 days × 2 series in half a card). This is readable, and the table view has the exact
  numbers.
