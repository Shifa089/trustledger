# ADR 0011: AWS SDK v2 (S3 only) for KYC document storage

- Status: Accepted
- Date: 2026-11-15
- Spec: docs/SPEC.md §2 FR-KYC-2, §8 M3 ("Document upload to S3 (LocalStack locally)"), §10

## Context

FR-KYC-2 stores KYC documents (PDF/JPG/PNG, at most 5 MB) in S3, never in the database, and officers
view them through presigned URLs valid for 5 minutes. LocalStack already runs in `infra/docker-compose.yml`.
The CLAUDE.md stack list has no S3 client and no Testcontainers LocalStack module, and CLAUDE.md requires an
ADR for every dependency outside that list. The user approved both on Day 5 Session B.

## Options

1. **Plain AWS SDK for Java v2**, `s3` module only, behind the `DocumentStorage` port. Explicit client
   and presigner beans in `kyc.infrastructure.S3StorageConfig`.
2. **Spring Cloud AWS** (`spring-cloud-aws-starter-s3`): auto-configured `S3Template`, but it brings
   another BOM whose releases must follow Spring Boot 4, and auto-configuration we would partly fight
   (credentials, endpoint per profile). We need four calls: put, delete, presign, create bucket.
3. **MinIO client**: S3-compatible, but production is AWS, and the AWS SDK is the reference client.

## Decision

Use **AWS SDK v2 2.56.0** through its BOM (`aws-sdk.version` in the parent POM):

- `software.amazon.awssdk:s3` with the Apache, Apache 5 and Netty HTTP clients excluded, plus
  `url-connection-client` (JDK `HttpURLConnection`): no extra HTTP stack for four simple calls.
- `org.testcontainers:testcontainers-localstack` (version from the Testcontainers BOM) for integration tests,
  with the same image as compose (`localstack/localstack:4.14`).

2.56.0 was the newest release more than two weeks old on Day 5 (released 2026-10-14).

Details:

- Every object is written with SSE-S3 (`AES256`) and `x-amz-checksum-sha256`, so S3 refuses bytes that do
  not match the SHA-256 we store.
- Credentials: static only when `trustledger.kyc.storage.access-key/secret-key` are set (LocalStack's dummy
  `test`/`test`); otherwise the SDK default chain (an ECS task role in M14). No keys in code or Git.
- The bucket is created at startup only when `trustledger.kyc.storage.create-bucket=true` (local profile and
  tests). In AWS it is Terraform's job, together with its public-access block and lifecycle rules.
- LocalStack runs with `S3_SKIP_SIGNATURE_VALIDATION=0`, so tampered and expired presigned URLs are refused
  locally just as in AWS.

## Consequences

- Only `kyc.infrastructure` imports `software.amazon.awssdk.*`; everything else uses `DocumentStorage`.
  Swapping the store means one new adapter.
- Every Spring integration test context now starts LocalStack too (once per run, shared context): a few
  seconds more on the first IT.
- The privacy module (M10) will reuse the same client for data-export files.
- One more dependency family to keep patched; the BOM keeps all SDK modules on one version.
