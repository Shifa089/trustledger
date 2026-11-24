# Changes

## DAY29 (F1): frontend skeleton, OIDC login, landing page

**Goal:** a React app in `frontend/` where each demo user logs in through Keycloak and sees navigation for their
role (build plan "Frontend and live demo", Day 29).

Branch: `feature/day29-frontend-f1` (from `master`, which has Day 26 Session B).

### Decisions

- **Stack as in the build plan's table** (ADR 0023). React Hook Form, Zod and Recharts are listed for Day 30 and not
  installed yet. npm resolved TypeScript 6.0 (typescript-eslint's peer range). `eslint-plugin-jsx-a11y` was left out
  because it doesn't support ESLint 10 yet.
- **Tokens in memory only.** oidc-client-ts `userStore` is `InMemoryWebStorage`. sessionStorage holds only the PKCE
  verifier and `state`, during the redirect.
- **Silent renew uses the refresh token, not an iframe**, so it needs no third-party cookies.
- **A reload forgets the tokens.** RequireAuth redirects to Keycloak, whose SSO cookie signs the user straight back
  in.
- **One API client for every call** (`src/api/client.ts`), with two instances: `coreApi` (8080) and `amlApi`
  (8081).
  - It adds the bearer token, a fresh `X-Correlation-Id` per request and an optional `Idempotency-Key`.
  - It turns a ProblemDetail into an `ApiError` with a friendly message per `errorCode`. Without a ProblemDetail it
    falls back by status. It keeps the correlation ID, using the echoed one if present, otherwise the one it sent.
  - A unit test reads both backends' `ErrorCode.java`, so a new code without a message fails the build.
- **Money in the browser is bigint paise** (`src/lib/money.ts`): parse and format the API's decimal strings, with
  Indian digit grouping.
- **ESLint enforces the CLAUDE.md frontend rules:** `fetch` only in the API client, no
  `localStorage`/`sessionStorage`, no `parseFloat`.
- **Roles come from `realm_access.roles` in the access token**, the same claim the backend reads.
  - `RequireRole` sends a wrong role to `/forbidden`.
  - Navigation lists only the user's sections. Screens for later days are shown as "soon".
- **The TEST MODE banner is in the root layout**, above every route, public or signed in.
- **The dev and preview ports are 5173 and 4173.** Both are in the Keycloak client (redirect, web origin,
  post-logout) and in the local CORS allowlists.
- **The AML service had no CORS at all.** It now has the same allowlist as core-app: an
  `AmlSecurityProperties.Cors` record with the same validation, `/api/**` only, no credentials, and empty by default.
- **Dev defaults live in `src/config.ts`, used only when `import.meta.env.DEV`.** They are not in a committed
  `.env.development`, because the repo ignores every `.env.*` except `.env.example`. A production build without
  values fails at startup.
- **CI job `frontend`:** npm ci, then lint, type-check, Vitest, build and Playwright. It uses only GitHub's actions
  (`setup-node@v7`). The job sits before `images`, so the images job's "no push/secrets" check doesn't see it.
- **`frontend/` is excluded from the Docker build context** of the Spring images.

### Files

**Backend**
- `risk-aml-service/.../shared/security/AmlSecurityProperties.java`: `cors.allowed-origins` with validation.
- `risk-aml-service/.../shared/security/SecurityConfig.java`: `.cors()` and a `CorsConfigurationSource` bean.
- `risk-aml-service/src/main/resources/application.yml`: empty allowlist. `application-local.yml`: 5173 and 4173.
  Test `config/application.yml`: 5173.
- `core-app/src/main/resources/application-local.yml`: 4173 added.
- `infra/keycloak/realm-trustledger.json`: `trustledger-web` redirect, web origin and post-logout for 4173.
- Tests: `AmlSecurityPropertiesTest` (new), plus 3 CORS cases in `AmlSecurityIT`: allowed preflight, foreign
  origin refused, actual request exposes `X-Correlation-Id` and still needs the role.

**Frontend** (`frontend/`)
- Tooling: Vite, TS (app, test and node configs), ESLint, Playwright, shadcn `components.json`, `.nvmrc`,
  `.env.example`.
- `src/api/`: client, errors, instances, types. `src/auth/`: oidc, roles, useRoles, RequireAuth, RequireRole.
  `src/lib/`: money, utils.
- `src/layouts/`: Root (banner), Public, App (role navigation, sign out). `src/pages/`: landing, callback, `/app`
  redirect, forbidden, not found, and the four role homes (core `GET /me`; compliance also `GET /aml/cases`).
- `public/architecture.svg`, `public/favicon.svg`.

**Tests**
- Vitest (84 tests): `apiClient.test.ts`, `errors.test.ts`, `RequireRole.test.tsx`, `roles.test.ts`,
  `oidc.test.ts` (no token in storage), `router.test.tsx` (banner, redirects, navigation per role, both services,
  sign out), `money.test.ts`, `queryClient.test.ts`.
- Playwright: `landing.spec.ts` (CI; PKCE redirect checked against a stubbed Keycloak discovery). `login.spec.ts`,
  tagged `@stack`, logs in for real as customer1 and auditor1 and checks storage. It runs with its own
  `playwright.stack.config.ts` (dev server on 5173, one worker). Picking the mode from `process.argv` didn't work,
  because Playwright's workers don't receive the CLI arguments.

### Verification

- `./mvnw verify`: 2,226 tests, 0 failures.
- `npm ci && npm run ci`: lint, type-check, 84 Vitest tests and the build pass. `npm run e2e`: 3 passed.
- Live stack (compose infra, core-app `local,demo`, AML `local`):
  - CORS preflight from 5173 gets 200 with Allow-Origin on both services; from a foreign origin, 403.
  - `npm run e2e:stack`: 2 passed, with a real Keycloak login for customer1 and auditor1.

**CI and docs**
- `.github/workflows/ci.yml`: `frontend` job. `dev/.dockerignore`: `frontend/`.
- `docs/adr/0023-frontend-stack.md`, CLAUDE.md "Frontend" section, README (stack, repository map, how to run, ADR
  list).

### Not done / noted

- **The AML service doesn't echo `X-Correlation-Id` over HTTP** (SPEC §5 "Headers"); core-app does. This gap
  already existed. CORS already exposes the header, so it is a small follow-up.
- **Officers and admin need an OTP**, so the automated real-login spec covers customer1 and auditor1. Officers are
  checked by hand.
