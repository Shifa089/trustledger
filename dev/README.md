# TrustLedger

A regulated digital wallet with a double-entry ledger, KYC, AML monitoring and a tamper-evident
audit trail. Java 25, Spring Boot 4 (modular monolith with Spring Modulith) plus a separate
risk/AML service over Kafka. Portfolio project: all users and data are fake, payments use Razorpay
test mode only.

- Specification: [docs/SPEC.md](docs/SPEC.md)
- Design decisions: [docs/adr/](docs/adr/)

> This README grows with each milestone. The full structure (architecture diagram, compliance
> mapping, test and performance results) arrives in M16.

## Run locally

From this directory (`dev/`), with Docker Desktop running:

```bash
cp .env.example .env            # then replace every change-me (see comments in the file)
docker compose -f infra/docker-compose.yml up -d
./mvnw install -DskipTests      # once
./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local
```

Build and run every test (Testcontainers starts PostgreSQL, Redis and Keycloak): `./mvnw verify`.

## Local authentication (Keycloak)

Keycloak runs at http://localhost:8180 (admin console login from `KC_BOOTSTRAP_ADMIN_*` in `.env`).
The realm `trustledger` is imported from [infra/keycloak/realm-trustledger.json](infra/keycloak/realm-trustledger.json)
when the container is **created**. After changing the realm file or the `TRUSTLEDGER_DEV_*` values
in `.env`, re-import with:

```bash
docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak
```

### Clients

| Client | Type | Use |
| --- | --- | --- |
| `trustledger-web` | Public, authorization code + PKCE (S256) | The browser app (`http://localhost:5173`). No password grant. |
| `trustledger-dev` | Public, **direct access grants (password grant)** | **Local testing only**: [http/auth.http](http/auth.http) and manual tests. The password grant hands the user's password to the client, so this client must never exist outside a developer machine. |

### Test users (fake, local only)

All six use the password in `TRUSTLEDGER_DEV_USER_PASSWORD` from your `.env`.

| User | Role | OTP required |
| --- | --- | --- |
| customer1, customer2 | CUSTOMER | no |
| officer1, officer2 | COMPLIANCE_OFFICER | **yes** |
| admin1 | ADMIN | **yes** |
| auditor1 | AUDITOR | no |

OTP (TOTP, 6 digits, 30 s) is enforced **by role** for compliance officers and admins (FR-ID-4), in
both the browser login and the password grant. For local use, officer1, officer2 and admin1 already
have a TOTP credential whose secret is `TRUSTLEDGER_DEV_TOTP_SECRET` from `.env`. Authenticator apps
and `oathtool` expect the secret in base32:

```bash
printf '%s' "$TRUSTLEDGER_DEV_TOTP_SECRET" | base32          # add this to an authenticator app once
oathtool --totp -b "$(printf '%s' "$TRUSTLEDGER_DEV_TOTP_SECRET" | base32)"   # or print the current code
```

Notes:

- A code can be used only once. Wait for the next 30-second window to log in the same user again.
- Brute-force protection is on: two failed logins within one second, or five in a row, lock the user
  for at least a minute (`user_temporarily_disabled` in the Keycloak log).
- Access tokens live 5 minutes. Refresh tokens rotate: each one works once.

### Calling the API

[http/auth.http](http/auth.http) (IntelliJ HTTP Client, environment `local`) gets a token for every
user and calls `GET /api/v1/ping`, which answers with the token's subject and mapped roles. Copy
`http/http-client.private.env.json.example` to `http/http-client.private.env.json` (git-ignored) and
put the test-user password in it.

With curl:

```bash
TOKEN=$(curl -s http://localhost:8180/realms/trustledger/protocol/openid-connect/token \
  -d grant_type=password -d client_id=trustledger-dev -d username=customer1 \
  --data-urlencode "password=$TRUSTLEDGER_DEV_USER_PASSWORD" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -s http://localhost:8080/api/v1/ping -H "Authorization: Bearer $TOKEN"
# {"status":"pong","subject":"<keycloak user id>","roles":["CUSTOMER"]}
```

With the same token:

```bash
curl -s http://localhost:8080/api/v1/me -H "Authorization: Bearer $TOKEN"
# {"id":"...","email":"c***@example.test","phone":null,"fullName":"T*** C*** O***","status":"ACTIVE","roles":["CUSTOMER"]}
curl -s http://localhost:8080/api/v1/me/consents -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"consents":[
    {"purpose":"TERMS","policyVersion":"2026-11-01","granted":true},
    {"purpose":"PRIVACY","policyVersion":"2026-11-01","granted":true}]}'
```

The first authenticated request creates the user row in `identity.users` (just-in-time provisioning).
Personal data is stored encrypted and returned masked. Rate limits per user: 20 requests/minute on
`POST /transfers` and `POST /topups` together, 100/minute on everything else; above that the API returns
429 `RATE_LIMITED` with `Retry-After`.

Swagger UI (local profile only, no token needed to open it): http://localhost:8080/swagger-ui.html
