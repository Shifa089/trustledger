# Changes

## DAY3 — Milestone M2 (part 1): Keycloak realm, API security, auth.http

**Goal:** real OAuth2/OIDC security. A Keycloak realm as code (roles, two clients, six fake users,
OTP required for staff, 5-minute tokens, rotating refresh tokens); core-app as a stateless JWT
resource server with role mapping, method security, path rules, deny by default, CORS and security
headers; an `http/auth.http` file that gets a token for every user and calls `/api/v1/ping`.
Identity module, JIT provisioning, `/me`, consents and rate limiting are part 2 (Day 4).

Branch: `feature/m2-security` (created from `feature/m1-shared`; not committed yet).

---

### 1. How to verify Day 3

Run these from `dev/`. Start **Docker Desktop** first.

| # | Command | Why it is necessary |
|---|---|---|
| 1 | Check `.env` has `TRUSTLEDGER_DEV_USER_PASSWORD` and `TRUSTLEDGER_DEV_TOTP_SECRET` | The realm file contains `${...}` placeholders instead of passwords; Keycloak fills them from `.env` at import. They were generated into your `.env`; on a new machine copy them from `.env.example` and choose values (TOTP secret: letters/digits). |
| 2 | `docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak` | The realm is imported only when the container is **created**. Recreate after every change to the realm file or the two variables. |
| 3 | Open http://localhost:8180, log in to the admin console, realm `trustledger` | See roles, clients, users, the `browser-role-otp` / `direct-grant-role-otp` flows and the token settings. |
| 4 | `./mvnw verify` | Full build: 254 core-app unit/slice tests, 84 core-app ITs (with a real Keycloak container importing the real realm file), AML IT, Modulith check. Same as CI. |
| 5 | `./mvnw -pl core-app verify -DskipUnitTests -Dit.test=KeycloakRealmIT` | Just the realm + real-token tests (about 1 minute; containers start once). |
| 6 | `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local` | core-app with `issuer-uri` = compose Keycloak, Swagger public, CORS for `http://localhost:5173`. |
| 7 | Copy `http/http-client.private.env.json.example` to `http/http-client.private.env.json`, set the password, run `http/auth.http` (environment `local`) | Token for each of the six users, then `GET /api/v1/ping` returns their subject and role. Officers and admin need a fresh TOTP code in `@otp` (see README). |
| 8 | `curl -i localhost:8080/api/v1/ping` | 401 ProblemDetail (`UNAUTHENTICATED`) with `WWW-Authenticate: Bearer` and `X-Correlation-Id`. |
| 9 | Customer token on `GET /api/v1/admin/reconciliation/run` | 403 ProblemDetail (`ACCESS_DENIED`): the URL rule blocks it before any controller exists. |
| 10 | http://localhost:8080/swagger-ui.html | Opens without a token in the local profile only. |

**Results on Day 3:** `./mvnw clean verify` BUILD SUCCESS: core-app 254 unit/slice tests and
84 ITs (`KeycloakRealmIT` 23, `SecurityMatrixIT` 52, `ApiVersioningIT` 5, `FlywaySchemasIT` 2,
`TrustLedgerApplicationIT` 2), AML IT 1/1, ModularityTests green. Live check against the compose
stack: all six users get tokens (staff only with OTP), `/api/v1/ping` returns the right role for
each, no token → 401, wrong role → 403, Swagger 200 in local.

---

### 2. What changed and why

#### Keycloak realm as code

| File | Change | Why |
|---|---|---|
| `infra/keycloak/realm-trustledger.json` | Full realm: roles CUSTOMER, COMPLIANCE_OFFICER, ADMIN, AUDITOR; default role (self-registration) = CUSTOMER | FR-ID-1 and SPEC §2 actors. Staff accounts get only their staff role (the test proves officers are not also customers): separation of duties. |
| ″ | Client `trustledger-web`: public, standard flow only, `pkce.code.challenge.method=S256`, redirect/web origin `http://localhost:5173` | A browser app cannot keep a client secret, so it is public; PKCE stops a stolen authorization code from being exchanged by someone else. |
| ″ | Client `trustledger-dev`: public, **direct access grants only**, described as LOCAL TESTING ONLY (also in README) | The password grant gives the client the user's password, so it is only acceptable for local scripts (`auth.http`, curl). |
| ″ | Audience mapper `trustledger-api` on both clients | The API accepts only tokens meant for it; without this, any token issued by the realm (for any client) would be accepted. |
| ″ | Custom flows `browser-role-otp` and `direct-grant-role-otp` with *Condition – user role* (COMPLIANCE_OFFICER, ADMIN) → OTP **required** | FR-ID-4. The default Keycloak flow asks for OTP only if the user has already configured it, so removing a credential would silently switch MFA off. Enforced by role, in both flows. |
| ″ | Pre-registered TOTP credential for officer1, officer2, admin1 (secret from `${TRUSTLEDGER_DEV_TOTP_SECRET}`) | Lets you log in as staff locally (password + 6-digit code) without enrolling an authenticator through the browser first. |
| ″ | `accessTokenLifespan` 300, `revokeRefreshToken` true, `refreshTokenMaxReuse` 0, `otpPolicyCodeReusable` false, `bruteForceProtected` true, `sslRequired` external | Short-lived access tokens; a stolen refresh token stops working after its first use; one OTP code = one login; password guessing is throttled. |
| ″ | Users with fake `@example.test` e-mails, first and last names, `emailVerified` | Keycloak 26's user profile otherwise adds a required action and the login fails with "Account is not fully set up". |
| ″ | Passwords are `${TRUSTLEDGER_DEV_USER_PASSWORD}` placeholders | CLAUDE.md "no secrets in Git", even for fake users. Keycloak substitutes environment variables at import. |
| `.env.example` | `TRUSTLEDGER_DEV_USER_PASSWORD`, `TRUSTLEDGER_DEV_TOTP_SECRET` with instructions | Documents the new local secrets without committing values. |

#### API security (`core-app`, package `com.trustledger.core.shared.security`)

| File | Change | Why |
|---|---|---|
| `SecurityConfig.java` | Main `SecurityFilterChain`: stateless, CSRF/form login/HTTP Basic/logout/request cache off; JWT resource server; `@EnableMethodSecurity` | No sessions or cookies, so CSRF cannot apply (a forged cross-site request has no bearer token). Keycloak does logins, the API only validates tokens. |
| ″ | Path rules in order: webhooks + `/actuator/health` public; `GET /api/*/admin/ledger/accounts/*/balance` ADMIN or AUDITOR; `/api/*/compliance/**` COMPLIANCE_OFFICER; `/api/*/admin/**` ADMIN; `/api/*/audit/**` AUDITOR; `/actuator/**` ADMIN; Swagger authenticated; `/api/**` authenticated; **everything else `denyAll`** | SPEC §5 roles per endpoint. The ledger-balance rule must come before `/admin/**`, otherwise auditors are blocked (first match wins). `/api/*/` instead of `/api/v1/` so a future v2 inherits the rules. ERROR dispatches are permitted so an error page can be rendered for an already-authorized request. |
| ″ | `JwtDecoder` bean: `NimbusJwtDecoder.withIssuerLocation` inside a `SupplierJwtDecoder`, validators = default (exp/nbf) + issuer + audience; startup fails if issuer or audience is missing | Explicit, in one place, instead of Boot's conditional auto-configuration (see "Fixes" below). Lazy: Keycloak's keys are fetched on the first request, so the app can start before Keycloak. |
| ″ | Headers: CSP `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, HSTS 1 year + subdomains (HTTPS only), plus Spring's `nosniff` and `Cache-Control: no-store` | A JSON API never needs to load scripts or be framed; balances and personal data must not be cached by browsers or proxies. |
| ″ | CORS from `trustledger.security.cors.allowed-origins`, only on `/api/**`; listed methods and headers (`Authorization`, `Content-Type`, `Idempotency-Key`, `X-Correlation-Id`); exposes `X-Correlation-Id`, `Location`; no credentials | Strict allowlist from config (SPEC §6). |
| ″ | Second chain (`@Order(1)`) for Swagger paths, only when `trustledger.security.swagger-public=true` | Swagger UI needs scripts and styles that the strict API CSP forbids. The flag is true only in `application-local.yml`; elsewhere Swagger needs a token (M13 will disable it on AWS). |
| `JwtRoleConverter.java` | `realm_access.roles` → `ROLE_*`, **allowlist** of the four roles, case-sensitive, malformed claim → no authorities | Keycloak's own roles (`default-roles-trustledger`, `offline_access`) and any role added in Keycloak later get no power until the code knows them. |
| `Role.java` | Enum of the four roles with `authority()` | One source of truth for role names, usable in later `@PreAuthorize` rules. |
| `SecurityProperties.java` | `@ConfigurationProperties("trustledger.security")` record: `cors.allowedOrigins`, `swaggerPublic`; constructor rejects `*`, blank, non-http(s), paths, user info | A wildcard or typo in the CORS list fails startup instead of opening the API. |
| `ProblemDetailSecurityHandler.java` | `AuthenticationEntryPoint` + `AccessDeniedHandler` writing the same ProblemDetail (`errorCode`, `correlationId`); delegates to the bearer-token handlers first for `WWW-Authenticate` | Security errors happen in the filter chain, before Spring MVC, so `GlobalExceptionHandler` never sees them. The token decoder's message is never echoed (it describes the token). |
| `shared/web/ProblemDetails.java` | Public factory extracted from `GlobalExceptionHandler.problem(...)` | Shared by the MVC advice and the security handler; same JSON shape everywhere. |
| `shared/web/PingController.java` | `GET /api/v1/ping` (moved from test code to main): `{status, subject, roles}` | `auth.http` needs a real endpoint to prove a token works end to end. Returns only the Keycloak user UUID and roles, no personal data. Replaced the test-only `PingTestController`. |
| `application.yml` | `spring.security.oauth2.resourceserver.jwt.audiences: trustledger-api`; `trustledger.security.cors.allowed-origins: []`, `swagger-public: false` | Secure defaults for every environment. |
| `application-local.yml` | `issuer-uri: ${TRUSTLEDGER_ISSUER_URI:http://localhost:8180/realms/trustledger}`, CORS `http://localhost:5173`, `swagger-public: true` | Local developer settings. |

#### Tests

| File | What it proves |
|---|---|
| `JwtRoleConverterTest` (12) | Each role mapped; Keycloak/unknown roles ignored; case-sensitive; duplicates removed; missing or malformed `realm_access` → no authorities; client roles and scopes ignored. |
| `AudienceValidatorTest` (3) | Token for `trustledger-api` accepted; other audience or none rejected with `invalid_token`. |
| `SecurityPropertiesTest` (14) | Closed defaults; exact origins accepted; `*`, wildcards, blanks, paths, user info, queries, non-http rejected; list immutable. |
| `SwaggerPublicLocalTest` (3, `@WebMvcTest`) | With the local flag: Swagger paths need no token and do not get the strict CSP; the API and actuator still return 401. |
| `SecurityMatrixIT` (52) | 401/403/200 for every path group × {no token, no roles, CUSTOMER, OFFICER, ADMIN, AUDITOR}; ledger balance for ADMIN and AUDITOR; method security (`@PreAuthorize`) active; webhooks and health public; actuator ADMIN-only; non-`/api` denied; 401/403 bodies are ProblemDetail with `errorCode`, `correlationId`, `WWW-Authenticate`; garbage token rejected by the real decoder without leaking its message; CORS allowed vs rejected origin; security headers; no session or cookie; webhook POST needs no CSRF token. Mock JWTs run through the production `JwtRoleConverter`. |
| `KeycloakRealmIT` (23, **real Keycloak**) | All six users get tokens with exactly their role (staff are not customers); officers and admin are **rejected without OTP** and with a wrong OTP, accepted with a TOTP computed in the test; token lifetime 300 s; `aud` contains `trustledger-api`; a reused refresh token is rejected; `trustledger-web` refuses the password grant and requires PKCE; real tokens call `/api/v1/ping` and get the right role; a real customer token still gets 403 on admin paths; tampered and `alg: none` tokens get 401. |
| `KeycloakContainer`, `KeycloakTokens` (test support) | Keycloak 26.8.2 (same image as compose) importing the real realm file with fake test-only values; token endpoint client and an RFC 6238 TOTP generator in plain JDK code. |
| `TestcontainersConfiguration` | Adds the Keycloak container and a `DynamicPropertyRegistrar` for `issuer-uri`. Every IT now runs against a real issuer; containers still start once per run (Keycloak adds about 20 s). |
| `ApiVersioningIT` | `/ping` (outside `/api`) is now **403** (deny by default) instead of 404; new case: unknown `/api/v1/...` path is still 404. |

#### Docs and developer files

| File | Change | Why |
|---|---|---|
| `http/auth.http` | Token for each user via `trustledger-dev`, then `/api/v1/ping`; officer password-only (expect 400); no token (401); wrong role (403); health (200) | The M2 deliverable; also a quick manual smoke test. |
| `http/http-client.env.json`, `http/http-client.private.env.json.example`, `.gitignore` | URLs committed; the password lives in the git-ignored private env file | No secrets in Git. |
| `README.md` | New: run locally + "Local authentication" (clients, the local-only warning for `trustledger-dev`, users, OTP setup, lockout notes, curl example) | The task asked to document the dev client in the README; SPEC §11 needs a README anyway. |
| `docs/adr/0002-keycloak-instead-of-custom-auth.md` | New ADR | Required by SPEC M2; records why Keycloak, role-based MFA, audience check and realm-as-code. |

**Fixes found while testing**

- **No `JwtDecoder` bean in the ITs.** Boot creates its decoder only if `issuer-uri` is set when
  auto-configuration *conditions* are evaluated. A `DynamicPropertyRegistrar` adds the property
  later (just before beans are created), so the condition saw nothing. Fix: declare the decoder in
  `SecurityConfig` (a better design anyway: explicit validators, fail-fast startup).
- **Keycloak returns 400, not 401,** for a failed password grant (`invalid_grant`). Test expectations adjusted.
- **Hand-built MockMvc skips servlet filters.** `webAppContextSetup(...)` only adds the filters you
  give it, so `X-Correlation-Id` was missing in the IT responses. The ITs now add `CorrelationIdFilter`
  before Spring Security, matching the real filter order.
- **Brute-force "quick login check" during the live check:** two failed staff logins within one
  second (password-only, then a wrong OTP from a broken shell script) locked officer1 for 60 s
  (`user_temporarily_disabled`). This is the protection working, now documented in the README.
- **`${role_offline-access}` descriptions** (Keycloak export defaults) would collide with
  environment-placeholder substitution at import; replaced with plain text.

---

### 3. Machine setup done on Day 3 (not in Git)

- **`.env`**: added `TRUSTLEDGER_DEV_USER_PASSWORD` and `TRUSTLEDGER_DEV_TOTP_SECRET` (random values).
- **Keycloak container recreated** so the new realm was imported.
- **Branch** `feature/m2-security` created.
- Still to do by you: create `http/http-client.private.env.json` from the example before using `auth.http`.

### 4. Deferred

- Identity module: `users` table (encrypted fields + blind indexes), JIT provisioning (FR-ID-2),
  `GET /me`, consents (FR-ID-3): Day 4 (M2 part 2).
- Bucket4j + Redis rate limiting (20/min money endpoints, 100/min others), 429 `RATE_LIMITED`: Day 4.
- Ownership checks against BOLA (`@walletAccess.isOwner(...)`): with the first owned resources (M3/M4).
- Per-endpoint `@PreAuthorize` role rules for customer endpoints (`/kyc`, `/wallets`, `/transfers`):
  added with each controller; today `/api/**` only requires authentication.
- Production Keycloak (hostname, HTTPS, real DB instead of dev-mode H2) and removing `trustledger-dev`: M14.
- Disable Swagger in the `aws` profile: M13.
- Permissions-Policy header: M13 hardening.

---

### 5. What to learn from Day 3

1. **Authentication vs authorization live in different places.** Keycloak proves *who* you are
   (password + OTP) and signs that into a JWT; the API only checks the signature, issuer, audience
   and expiry, then decides *what* you may do from the roles. The API never sees a password.
2. **A JWT is only as trustworthy as the checks you run on it.** Signature (JWKS) stops forgery,
   `iss` stops tokens from another Keycloak, `aud` stops tokens meant for another app, `exp` limits
   theft. Look at `KeycloakRealmIT.tamperedTokenIsRejected` and `unsignedTokenIsRejected`.
3. **Deny by default means an allowlist, in order.** Spring checks request matchers top to bottom and
   the first match wins, so specific rules go before general ones (ledger balance before `/admin/**`),
   and the last rule is `denyAll`. The role converter is an allowlist too.
4. **Defence in depth:** URL rules are the coarse gate, `@PreAuthorize` is the fine gate per
   endpoint, and ownership checks (later) stop one customer from reading another's data (BOLA).
5. **Security errors don't reach `@RestControllerAdvice`.** They are thrown in the servlet filter
   chain before MVC, so you need an `AuthenticationEntryPoint` and `AccessDeniedHandler` to keep the
   error format consistent.
6. **Why no CSRF protection here:** CSRF abuses cookies the browser attaches automatically. A bearer
   token in the `Authorization` header is never attached automatically, so a stateless API is not
   exposed (the moment you add cookie sessions, CSRF protection must come back).
7. **Test the configuration, not just the code.** The realm JSON is configuration; `KeycloakRealmIT`
   starts the real Keycloak with the real file, so a broken flow or a missing mapper fails the build.
8. **Spring context lifecycle matters:** auto-configuration conditions are evaluated before
   `DynamicPropertyRegistrar` values exist. When something "works in the app but not in tests",
   ask *when* each property becomes visible.
