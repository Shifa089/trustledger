# ADR 0002: Keycloak (OIDC) instead of custom authentication

- Status: Accepted
- Date: 2026-11-13
- Spec: docs/SPEC.md §2 (FR-ID-1, FR-ID-4), §6 "Security", §8 M2

## Context

TrustLedger is a regulated wallet. Customers register and log in; compliance officers and admins
need multi-factor authentication (FR-ID-4); the API must never handle passwords (FR-ID-1). Login
is the most attacked part of any financial app: password storage, brute-force protection, MFA
enrolment, session and refresh-token handling, account recovery. Getting any one of these wrong
is a breach.

## Options

1. **Keycloak as the OpenID Connect provider**, core-app as a stateless OAuth2 resource server that
   only validates JWTs.
2. **Custom auth in core-app**: a users table with BCrypt hashes, a login endpoint issuing our own
   JWTs, TOTP via a library, refresh tokens in Redis.
3. **Managed IdP** (AWS Cognito, Auth0): no server to run, but a cloud account is needed for local
   development and tests, and the free tiers limit MFA or custom flows.

## Decision

Use **Keycloak 26** (same image locally, in Testcontainers and later on AWS) with realm
`trustledger`, kept as code in `infra/keycloak/realm-trustledger.json` and imported at startup.

- **Clients:** `trustledger-web` (public, authorization code + PKCE S256, no password grant) and
  `trustledger-dev` (password grant, **local testing only**, never created outside a developer machine).
- **Roles:** realm roles CUSTOMER, COMPLIANCE_OFFICER, ADMIN, AUDITOR. Self-registered users get
  CUSTOMER through the default role; staff accounts have only their staff role.
- **MFA by role:** custom browser and direct-grant flows use *Condition – user role* so OTP is
  *required* for COMPLIANCE_OFFICER and ADMIN. Keycloak's default flow only asks for OTP if the user
  has already configured it, so deleting a staff member's OTP credential would silently disable MFA.
- **Tokens:** access tokens live 5 minutes; refresh tokens rotate and cannot be reused; every token
  carries audience `trustledger-api`, which the API requires.
- **Brute-force protection** on; OTP codes are single-use.
- **No secrets in Git:** test-user passwords and the dev TOTP secret are `${...}` placeholders filled
  from `.env` at import.
- **API side:** `SecurityConfig` validates signature (issuer JWKS), expiry, issuer and audience; maps
  `realm_access.roles` to `ROLE_*` through an allowlist (`JwtRoleConverter`); the principal is `sub`.

## Consequences

- The API stores no passwords and no sessions. Password policy, lockout, MFA enrolment and recovery
  are Keycloak configuration, not code we have to test and patch ourselves.
- One more service to run (about 500 MB RAM locally; a container or managed instance on AWS) and to
  upgrade. The integration tests start a real Keycloak (about 20 s once per run) so the realm file
  is tested, not just the Java code.
- Realm changes are made in the JSON file and re-imported (`--force-recreate keycloak`); changes made
  in the admin console are lost on re-import unless exported back.
- The `users` row in core-app is created just-in-time from the JWT (`sub`) on the first request
  (FR-ID-2, Day 4); Keycloak remains the source of truth for credentials and roles.
- On AWS the issuer URL changes; only `spring.security.oauth2.resourceserver.jwt.issuer-uri` and the
  clients' redirect URIs differ per environment.
