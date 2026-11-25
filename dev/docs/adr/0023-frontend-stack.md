# ADR 0023: React + TypeScript web app with OIDC (PKCE) and tokens in memory

- Status: Accepted
- Date: 2026-11-24
- Spec: docs/SPEC.md §2 (actors), §5 (API, headers, error format); Build plan "Frontend and live demo" (Days 29-34);
  CLAUDE.md Workflow ("Do not add dependencies not listed above without asking and writing an ADR")

## Context

TrustLedger is a backend portfolio project, but an interviewer should be able to click through it. The build plan
adds six days for a thin web client over the existing API, plus a public demo. The scope rule is a clean,
consistent UI and nothing more: no custom design system, no animations, no state framework beyond what is listed.

The browser is the least trusted place in the system. It holds bearer tokens for a payments API, and anything an
injected script can reach, an attacker can reach.

## Decision

**Stack** (`dev/frontend/`, Node from `.nvmrc`):

| Concern | Choice | Why |
| --- | --- | --- |
| Framework | React 19 + TypeScript 6 (strict, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`) with Vite 8 | The most common frontend stack; fast builds |
| Routing | React Router 8 (data router) | Role-based routes |
| Server state | TanStack Query 5 | Caching, retries, loading and error states with little code |
| UI | Tailwind CSS 4 + shadcn/ui (copied components, Radix Slot, lucide icons) | A professional look without design work |
| Login | OIDC Authorization Code + PKCE via react-oidc-context (oidc-client-ts) | Standard browser login |
| Forms (Day 30+) | React Hook Form + Zod, joined by `@hookform/resolvers` | Client-side rules that mirror the backend (PAN, mobile, amount) |
| Charts (Day 30+) | Recharts | Simple balance and activity charts |
| Unit tests | Vitest + React Testing Library + MSW | Component tests against mocked API responses |
| End-to-end | Playwright | Real browser tests, locally and against the live site |
| Lint | ESLint (typescript-eslint strict-type-checked, react-hooks, react-refresh) | Catches mistakes and enforces the rules below |

Libraries are added on the day they are first used; React Hook Form, Zod and Recharts are not installed in F1.
Day 30 (F2) added them, plus `@hookform/resolvers`, the official package that connects a Zod schema to React Hook
Form (`zodResolver`). It is a thin adapter from the React Hook Form authors; writing our own would only re-implement
it. No other UI dependency was added: input, label, select and progress bar are small local components on native
elements (no Radix packages).

**Uploads.** `fetch` can't report upload progress, so the API client has one `upload()` method built on
`XMLHttpRequest`, with the same bearer token, correlation ID and ProblemDetail mapping as every other call. ESLint
bans `XMLHttpRequest`, like `fetch`, everywhere except `src/api/client.ts`.
`eslint-plugin-jsx-a11y` was left out because it doesn't support ESLint 10 yet.

**Login.** Keycloak public client `trustledger-web`, Authorization Code with PKCE S256; no implicit flow, no
password grant, no client secret.
- **Tokens:** access, ID and refresh tokens are kept in memory (`InMemoryWebStorage`).
- **The one use of sessionStorage:** oidc-client-ts's state store, for the PKCE `code_verifier` and `state` during
  the redirect. It holds no token and is removed on the callback.
- **Silent renew:** redeems the refresh token before the 5-minute access token expires. There is no session
  iframe, because that needs third-party cookies.
- **Logout:** `signoutRedirect` ends the Keycloak session too.

**API client** (`src/api/client.ts`), used for every call:
- **Headers:** adds the bearer token and a fresh `X-Correlation-Id`, plus `Idempotency-Key` when the caller passes
  one.
- **Base URLs:** a separate base URL for core-app and for the AML service.
- **Errors:** turns a ProblemDetail into an `ApiError` whose message is a friendly text per `errorCode`. A unit test
  reads both backends' `ErrorCode` enums, so a new code without a message fails the build.

**Idempotency keys (Day 31).** The screen that moves money owns the key, not the client:
- Send money makes one key when the customer presses "Review". "Send" and "Try again" reuse it, and going back to
  edit throws it away.
- Add money makes one key per form submission and reuses it when the same amount is submitted again after a failure.
- A synchronous in-flight flag (a ref) drops a second click before the button re-renders as disabled.
- The backend replays a stored 2xx for a known key and releases the key on any non-2xx, so a retry is always safe.

**Razorpay Checkout (Day 31).** Add money opens Razorpay's hosted Checkout script
(`https://checkout.razorpay.com/v1/checkout.js`).
- It is loaded on first use by `src/features/topup/razorpay.ts`. It is not an npm dependency, because Razorpay only
  ships it as a hosted script. The backend's local `checkout.html` already used it (M7).
- Only the public test key ID and the order ID from `POST /topups` reach the browser.
- What Checkout reports is informational only: the wallet is credited by the signed webhook or the reconciler.
- A deployment with a CSP (Day 33) must allow `script-src https://checkout.razorpay.com` and
  `frame-src https://api.razorpay.com`.

**Roles in the UI.** `realm_access.roles` is read from the access token only to pick routes and navigation
(`RequireRole`). The server still checks role and ownership on every request.

**Rules enforced by ESLint:**
- `fetch` is only allowed in the API client.
- `localStorage` and `sessionStorage` are banned (with one commented exception, the state store above).
- `parseFloat` is banned. Money is a bigint of paise in `src/lib/money.ts`, parsed from and formatted to the API's
  decimal strings.

**Backend changes:**
- risk-aml-service gets the same CORS allowlist as core-app: `trustledger.security.cors.allowed-origins` for
  `/api/**`, exact origins only, no credentials, and empty by default.
- The local profile of both services and the Keycloak client allow the Vite dev server (5173) and `vite preview`
  (4173).

**CI.** A `frontend` job runs npm ci, lint, type-check, unit tests, build and Playwright, using only GitHub's own
actions (ADR 0021). Playwright runs against the built app with no backend: Keycloak's discovery is stubbed to check
the PKCE redirect. The real-login specs are tagged `@stack` and run locally with `npm run e2e:stack`.

## Consequences

- **XSS can't read tokens from storage**, though an injected script could still act in the page while it runs. CSP
  and React's escaping remain the main defence.
- **A full page reload forgets the tokens.** The app redirects to Keycloak again, which signs the user straight back
  in from its SSO cookie (one round trip, no password).
- **Node is a second toolchain in the repo.** Its dependencies are locked by `package-lock.json` and installed with
  `npm ci`. `frontend/` is excluded from the Docker build context of the two Spring images.
- **The UI and backend role checks can drift apart.** Drift could only hide or show a link; it cannot grant access.

## Alternatives considered

- **Tokens in localStorage:** they survive a reload, but any XSS can steal them and use them elsewhere until they
  expire. Rejected for a payments app.
- **Backend-for-frontend with an HttpOnly session cookie:** the strongest option, but it brings back sessions,
  CSRF protection and a new service. That is too much for six days and against the stateless resource-server design
  (SPEC §6). It is a good answer to "what would you do in production".
- **Angular:** a fine alternative for banks that use it; the screens and days stay the same.
- **Hand-written fetch calls per screen:** each one would need its own copy of the headers and the error mapping.
  The single client keeps them in one place.
