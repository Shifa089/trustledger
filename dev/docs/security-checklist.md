# Security checklist: OWASP API Security Top 10 (2023)

Milestone M13 (SPEC §2 "Security: OWASP API Top 10 addressed; deny by default; personal data never in logs").
Each item is mapped to TrustLedger's controls and to the tests that prove them. A control without a test is
called out as such, and so is every gap we know about.

`SecurityChecklistTest` keeps this file honest: all ten items must be present, and every test class named here
must exist in the repository.

How the layers fit together:

1. **Keycloak** authenticates people (OIDC, OTP for staff). The API never sees a password.
2. **`SecurityConfig`** (one per application) checks the JWT, then applies one URL rule per endpoint. It ends in
   `denyAll()`, so an endpoint nobody wrote a rule for is refused.
3. **`@PreAuthorize`** on every controller repeats the role. **Application services** check ownership with the
   caller's ID from the token (`sub`), never with an ID taken from the request.
4. **CI** fails on fixable CRITICAL dependency vulnerabilities (Trivy) and on secrets anywhere in Git history
   (gitleaks). See ADR 0021.

---

## API1:2023 Broken Object Level Authorization

**Risk here:** customer A reads or moves customer B's wallet, transfer, top-up, KYC case or data request by
changing an ID in the URL.

**Controls**
- Customer endpoints are scoped to the caller: `/wallets/me`, `/kyc/me`, `/me/...`. The wallet comes from the
  token's subject, not from the request.
- Endpoints that take an ID (`GET /transfers/{id}`, `GET /topups/{id}`) load the record *for the caller's wallet*.
  Someone else's ID gives 404, the same as an ID that doesn't exist, so the response doesn't reveal that the ID is
  real.
- Transaction history filters by the caller's wallet in SQL. A cursor from another customer can't widen it.
- Staff self-dealing is blocked as well: an officer can't review their own KYC case or unfreeze their own wallet.

**Tests**
- `TransferControllerIT` (`anotherCustomerCannotReadTheTransfer`)
- `TopupControllerIT` (`ownerCanReadTheTopupAndNobodyElseCan`)
- `WalletControllerIT` (`eachCustomerSeesOnlyTheirOwnWallet`, `officerCannotUnfreezeTheirOwnWallet`)
- `TransactionHistoryIT` (`anotherCustomersRowsAreNeverReturned`)
- `MinKycIT` (`usersOnlySeeTheirOwnCases`)
- `ComplianceKycIT` (`anOfficerCannotReviewTheirOwnCase`), `KycCaseTest`, `WalletTest`
- `IdempotencyServiceIT` and `IdempotencyInterceptorIT` (`keysArePerUser`): one user's key can't replay another
  user's response.

**Gaps:** none known.

## API2:2023 Broken Authentication

**Risk here:** forged, expired or misdirected tokens accepted; staff accounts protected by a password alone.

**Controls**
- Keycloak handles identity (ADR 0002). The web client uses authorisation code with PKCE, so it has no password
  grant. Access tokens live 5 minutes, and refresh tokens rotate and can be used only once.
- OTP (MFA) is required for COMPLIANCE_OFFICER and ADMIN (FR-ID-4).
- `SecurityConfig.jwtDecoder` checks the RS256 signature against Keycloak's JWKS, plus `exp`/`nbf` (60 s skew),
  the issuer and the audience (`trustledger-api`). A token issued for another client of the same realm is refused.
- The principal is the Keycloak `sub`, never an e-mail address or a username.
- A 401 is a ProblemDetail with a `Bearer` challenge. The decoder's error text never reaches the response.
- The Razorpay webhook has no JWT. It is authenticated by HMAC-SHA256 over the raw body, compared in constant
  time (FR-P-5).

**Tests**
- `KeycloakRealmIT`: real Keycloak, covering `staffCannotLogInWithPasswordOnly`, `staffCannotLogInWithAWrongOtp`,
  `webClientRequiresPkce`, `refreshTokensRotateAndCannotBeReused`, `tamperedTokenIsRejected` and
  `unsignedTokenIsRejected`.
- `AudienceValidatorTest` (in both applications)
- `SecurityMatrixIT` (`invalidBearerTokenIsRejectedByTheRealDecoder`, `unauthenticatedIsProblemDetailWithBearerChallenge`)
- `RazorpaySignatureVerifierTest`, `RazorpayWebhookControllerIT` (`invalidSignatureIsRejectedStoredAsInvalidAndNotProcessed`)

**Gaps:** account lockout and brute-force protection are Keycloak realm settings, not covered by our tests.

## API3:2023 Broken Object Property Level Authorization

**Risk here:** a response exposes fields the caller shouldn't see (personal data, internal state), or a request
sets a field it shouldn't (mass assignment, for example `status` or `senderWalletId`).

**Controls**
- Request and response types are records built by `of(...)` factories (ADR 0016). Entities never leave their
  module and have no public setters, so the HTTP layer can't bind a body onto an entity.
- Request records contain only what the caller may choose. The sender, wallet, status and limits come from the
  token and the server, never from the body. A JSON field that the record doesn't declare has nothing to bind to,
  so it can't set anything.
- Personal data is encrypted at rest (ADR 0009) and masked in views, notifications, audit snapshots and logs.
  Staff queues list cases without personal data.
- Validation errors list the field and the rule, never the rejected value. Database errors never include row
  values.
- Webhook payloads are stripped of personal fields before they are stored.

**Tests**
- `ArchitectureRulesTest` (`requestAndResponseTypesAreRecords`, `entitiesHaveNoPublicSetters`, `entitiesLiveInDomain`)
- `MeControllerIT` (`returnsMaskedProfileAndCreatesTheUser`), `IdentityMigrationIT` (`personalDataIsStoredOnlyAsCiphertext`)
- `ComplianceKycIT` (`queueListsFullCasesOfOneStatusOldestFirstWithoutPersonalData`)
- `AuditMaskerTest`, `MaskingTest`, `NotificationTemplatesTest`, `UserProvisioningServiceTest` (`tokenIdentityNeverPrintsPersonalData`)
- `GlobalExceptionHandlerTest` (`bodyValidationListsViolationsWithoutRejectedValues`), `DatabaseErrorDetailIT`
- `WebhookPayloadsTest` (`removesPersonalFieldsAtAnyDepth`)

**Gaps:** no test sends an extra field such as `"status"` in a transfer body to show it is ignored. The record
type makes binding impossible, but a test would make that explicit.

## API4:2023 Unrestricted Resource Consumption

**Risk here:** one client exhausts the database, the payment gateway or storage, or sends a huge body or page size.

**Controls**
- Bucket4j rate limits on Redis per user: a general limit, plus a stricter one for money-moving requests (20 per
  minute). Over the limit gives 429 with `Retry-After`.
- Every page size has a cap, enforced by the service. Transaction history, the largest list, uses a keyset cursor,
  so a deep page costs the same as the first.
- KYC uploads are at most 5 MB each (checked at startup and per file), with a limit on documents per case. The
  file type is checked by magic bytes.
- Idempotent request bodies and webhook bodies have size limits.
- Daily and monthly money limits per KYC tier (FR-W-2) cap what one account can move.
- Calls to the gateway have timeouts, retries only for idempotent GETs, and a circuit breaker (ADR 0014).

**Tests**
- `RateLimitIT` (`twentyFirstTransferInAMinuteIsRateLimited`, `hundredAndFirstOrdinaryRequestIsRateLimited`), `RateLimitFilterTest`
- `AuditQueryServiceTest` (`anOversizedPageIsRefused`), `KycReviewServiceTest`, `HeldTransferServiceTest`
- `KycDocumentServiceTest` (`oneByteOverTheLimitIsTooLargeExactlyTheLimitIsFine`), `KycDocumentPropertiesTest`, `FullKycIT` (`theDocumentLimitPerCaseIsEnforced`), `DocumentFormatTest`
- `IdempotencyInterceptorIT` (`bodyAboveTheLimitIs400`), `PaymentsPropertiesTest`
- `LimitCheckerTest`, `TransferServiceIT`
- `RazorpayGatewayAdapterTest` (`slowGatewayTimesOutAtTheLimitNotAtTheDelay`, `rejectionsDoNotOpenTheCircuit`)

**Gaps**
- The load test (`docs/perf.md`) shows the laptop tops out at about 55 to 80 transfers per second. The rate limit
  is per user, so many users at once can still saturate the database pool.
- There is no global limit or limit per IP. That belongs at the reverse proxy (M14).

## API5:2023 Broken Function Level Authorization

**Risk here:** a customer calls an officer or admin function, or a new endpoint ships without any rule.

**Controls**
- One URL rule per endpoint group in each `SecurityConfig`, ending in `denyAll()`. A new endpoint is refused until
  someone writes its rule. The rules use `/api/*/...`, so a future version inherits them.
- `@PreAuthorize` on every controller class or method, with the same role. The AML case controller and `/ping` got
  theirs in M13 when the new coverage test found them missing.
- Maker-checker for KYC approval: a different officer must approve (FR-KYC-3).
- Actuator: only health probes are public. Everything else is ADMIN, and `env` is not exposed.

**Tests**
- `EndpointAuthorizationCoverageIT` and `AmlEndpointAuthorizationCoverageIT` (new in M13):
  - every request mapping Spring MVC knows must be in the expected table;
  - the URL rules must grant exactly the expected callers;
  - at least one caller must be allowed, so the endpoint isn't falling through to `denyAll()`;
  - `@PreAuthorize` must grant the same roles.
- `SecurityMatrixIT` and `AmlSecurityIT`: 401, 403 or past security, for every role on every path group.
- `ComplianceKycIT` (`sameOfficerVerifyingAndApprovingIs409MakerCheckerViolation`)
- `ComplianceTransferControllerIT` (`aCustomerCannotDecideNotEvenOnTheirOwnTransfer`)
- `ActuatorEndpointsIT`, `JwtRoleConverterTest`

**Gaps:** none known.

## API6:2023 Unrestricted Access to Sensitive Business Flows

**Risk here:** automated or repeated use of a legitimate flow: double spending, smurfing just under limits, mule
fan-out, replayed webhooks.

**Controls**
- An `Idempotency-Key` is required on money-moving POSTs. The same key gives the stored response, the same key
  with another body gives 422, and a key that is still in progress gives 409.
- Each transfer is one database transaction with wallets locked in ascending ID order (ADR 0004). The balance
  can't go negative and can't be spent twice.
- Tier limits per day and month. Large transfers are held for officer review.
- The AML service (velocity, structuring, large amount, fan-out rules) raises cases and can freeze a wallet over
  Kafka (ADR 0007).
- Webhooks are deduplicated by event ID, and the amount must match the top-up.
- KYC needs maker-checker approval before FULL limits apply.

**Tests**
- `IdempotencyInterceptorIT` (`tenConcurrentDuplicatesRunTheHandlerOnce`), `IdempotencyServiceIT` (`twentyConcurrentClaimsOfOneKeyHaveExactlyOneWinner`)
- `TransferConcurrencyIT` (`hundredConcurrentTransfersWithBalanceForFiftyExactlyFiftySucceed`), `DeadlockFreedomIT`
- `HeldTransferServiceIT` (`concurrentDecisionsOnOneTransferProduceExactlyOne`)
- `RuleEngineIT`, `StructuringRuleTest`, `VelocityRuleTest`, `FanOutRuleTest`, `FreezeRequestPublishingIT`, `AccountFreezeRequestedConsumerIT`
- `TopupSettlementServiceTest` (`theSamePaymentAgainIsIgnored`, `anAmountThatDoesNotMatchTheTopupIsIgnored`)

**Gaps:** no device fingerprinting or bot detection. That is out of scope for a portfolio project.

## API7:2023 Server Side Request Forgery

**Risk here:** the server fetches a URL that an attacker chose.

**Controls**
- No endpoint takes a URL, and nothing fetches a URL from user input. The only outbound HTTP targets are the
  Razorpay base URL and the S3 endpoint, both from configuration.
- Razorpay IDs that go into a request path are checked against a strict format before any request is sent, so a
  crafted ID can't change the path.
- The gateway client never follows redirects.
- S3 download links are presigned by the server for our own bucket, and they expire after 5 minutes.

**Tests**
- `RazorpayGatewayAdapterTest` (`malformedIdsAreRefusedBeforeAnyRequest`)
- `S3DocumentStorageIT` (`anExpiredLinkIsRefused`)

**Gaps:** no test checks that `followRedirects(NEVER)` is set on the gateway client. Nothing tests the absence of
URL-taking features either. A future feature that fetches a URL needs an allowlist and its own test.

## API8:2023 Security Misconfiguration

**Risk here:** missing security headers, permissive CORS, exposed actuator or Swagger, verbose errors, vulnerable
libraries.

**Controls**
- Security headers on every response, errors included:
  - CSP `default-src 'none'; frame-ancestors 'none'`
  - `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`
  - `Cache-Control: no-store`
  - HSTS for 1 year with sub-domains, over HTTPS
- Stateless: no session and no cookies. CSRF is off for that reason.
- CORS is an allowlist from configuration, only for `/api/**`, without credentials.
- Swagger UI and the manual checkout page are public only with the local profile.
- Errors are ProblemDetails with a correlation ID. A 500 never shows the exception message.
- Unsupported API versions and unknown paths are refused before routing.
- CI: Trivy fails on fixable CRITICAL vulnerabilities in the shipped jars (ADR 0021).

**Tests**
- `SecurityHeadersIT` (new in M13): every kind of response (200, 401, 403 from a role, 403 from `denyAll()`,
  400, 404, public webhook, health) carries the full header set, and HSTS is sent only over HTTPS.
- `AmlSecurityIT` (`securityHeadersAreSet`)
- `SecurityMatrixIT`: the CORS, stateless and CSRF tests.
- `SwaggerPublicLocalTest`, `CheckoutPageLocalTest`, `ActuatorEndpointsIT`
- `GlobalExceptionHandlerTest` (`unexpectedErrorIsGeneric500WithoutLeakingMessage`), `ApiVersioningIT`
- `CiSecurityScanTest` (new in M13): the scans stay strict and pinned.

**Gaps**
- Trivy reports 6 HIGH findings, which don't fail CI: lz4-java 1.11.2 and snappy-java 1.1.10.7, both pulled in
  through kafka-clients. Fixed versions exist. Upgrading them is a dependency change, so it waits for approval
  (see the Day 24 Session B notes).
- Container images are scanned in M14, when the Dockerfiles exist.

## API9:2023 Improper Inventory Management

**Risk here:** forgotten, old or undocumented endpoints stay reachable.

**Controls**
- One API version, in the path (`/api/v1`). Other versions get 400, and unversioned paths are denied.
- OpenAPI is generated from the code (ADR 0010), and every endpoint has an IntelliJ `.http` file in `http/`.
- The coverage tests list every endpoint Spring MVC actually serves. Adding or removing one fails the build until
  the expected table, the URL rule and the matrix are updated, so the inventory can't drift from the code.

**Tests**
- `EndpointAuthorizationCoverageIT`, `AmlEndpointAuthorizationCoverageIT`
- `ApiVersioningIT` (`openApiDocumentListsVersionedPathAndBearerScheme`, `unversionedPathIsDeniedByDefault`)

**Gaps:** none left from Day 25 (closed on Day 26 B):
- `GET /admin/ledger/accounts/{code}/balance` now has its controller (`LedgerAccountController`, ADMIN and AUDITOR
  by URL rule and `@PreAuthorize`). The test probe for it is gone.
- `GET /aml/alerts` is built with its own rule.
- Both OpenAPI documents (core and AML) are generated from the code.

Springdoc is still on outside `local` (behind a token). Switching it off in a deployed profile belongs to the
deferred AWS half of M14.

## API10:2023 Unsafe Consumption of APIs

**Risk here:** we trust what Razorpay, Keycloak or Kafka send us.

**Controls**
- Razorpay webhooks:
  - the HMAC is verified over the raw bytes before parsing;
  - every delivery is stored (an invalid one too, as rejected) and deduplicated;
  - unknown event types are ignored.
  - A capture is credited only when the order is known and the amount matches the top-up.
- Razorpay API responses:
  - mapped into our own types;
  - an unknown status maps to UNKNOWN, not to an error;
  - errors keep only the gateway code and never the secret.
- Resilience: timeouts, a circuit breaker, and retries only for idempotent GETs. A pending top-up is reconciled
  later by a job, not trusted on the first try.
- Kafka: contracts are versioned and validated. A record that fails its own checks or can't be read goes to the
  DLT (ADR 0007), and consumers are idempotent.

**Tests**
- `RazorpayWebhookControllerIT` (`signatureOfReformattedJsonIsRejected`, `unknownTypeIsStoredAndIgnored`), `WebhookServiceTest`, `WebhookProcessorTest`
- `TopupWebhookSettlementIT` (`aCaptureWithADifferentAmountIsIgnored`, `aCaptureForAnUnknownOrderIsAcknowledgedAndIgnored`)
- `RazorpayGatewayAdapterTest` (`unknownStatusMapsToUnknownInsteadOfFailing`, `wrongKeysAreRejectedWithoutLeakingTheSecret`), `PaymentGatewayIT`, `PendingTopupReconcilerIT`
- `TransactionFactConsumerIT`, `AccountFreezeRequestedConsumerIT`, `EventContractJsonTest`

**Gaps:** none known.

---

## Supply chain and secrets (CI, ADR 0021)

| Check | Where | Fails the build when |
| --- | --- | --- |
| Trivy dependency scan | `dependency-scan` job, over the two Spring Boot jars | a CRITICAL vulnerability has a fixed version (exceptions in `.trivyignore`, each with an expiry) |
| gitleaks secret scan | `secret-scan` job, over every commit on every branch | any finding not covered by the narrow allowlist in `/.gitleaks.toml` |
| Scanner pinning | both jobs | `CiSecurityScanTest` fails if an image isn't pinned by digest or a third-party action is used |

Personal data never goes into logs. The masking helpers are tested by `MaskingTest`, and the logging rule is
enforced in code review. See CLAUDE.md.
