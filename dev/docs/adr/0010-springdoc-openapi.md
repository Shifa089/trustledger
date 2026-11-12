# ADR 0010: springdoc-openapi for the API description

- Status: Accepted
- Date: 2026-11-12
- Spec: docs/SPEC.md §8 M1 ("springdoc OpenAPI"), §5 "API specification"

## Context

SPEC M1 asks for an OpenAPI description of the REST API, but the CLAUDE.md stack list did not
include a library for it, and CLAUDE.md requires an ADR for every dependency outside that list.

Reviewers, the M16 `http/` collection and future clients need a machine-readable contract that
stays in sync with the code: paths under `/api/v1`, the Keycloak bearer scheme, request and
response records, and the ProblemDetail error format.

## Options

1. **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui`): generates OpenAPI 3.1 at runtime
   from Spring MVC mappings, Bean Validation annotations and records; ships Swagger UI.
2. **Hand-written `openapi.yaml`** (design-first): a precise contract, but it drifts from the code
   unless a contract-test step is added, which is extra work for a single developer.
3. **No OpenAPI**: least work, but no published contract; reviewers must read controllers.

## Decision

Use **springdoc-openapi 3.2.0**, version managed once in the parent POM (`springdoc.version`).

- 3.2.0 is built against Spring Boot 4.1.2, the version this project uses.
- It understands Spring Framework 7 API versioning: a mapping with `version = "1"` under the
  `/api/v{version}` prefix is documented as `/api/v1/...` (verified by `ApiVersioningIT`).
- `OpenApiConfig` declares one HTTP bearer (JWT) scheme `bearerAuth` and applies it globally.

## Consequences

- `/v3/api-docs` and `/swagger-ui.html` exist. Until M2 they sit behind Spring Boot's default
  security. M2 decides who may read them; M13 hardening should disable them in the `aws` profile
  (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`) or restrict them to ADMIN.
- The document is generated from code, so it cannot drift, but it is only as good as the
  annotations: public endpoints (the Razorpay webhook in M7) must opt out of `bearerAuth`.
- One more runtime dependency (plus the swagger-ui webjar) to keep patched when Spring Boot is upgraded.
