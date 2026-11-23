# Changes

## DAY25 Session A (M14, without AWS): container images, smoke test, image build in CI

**Goal** (SPEC §8 M14, §10):
1. Multi-stage Dockerfiles for both apps: a Maven wrapper build stage, an eclipse-temurin 25 JRE run stage, a
   layered jar, a non-root user, and a HEALTHCHECK on `/actuator/health`.
2. `scripts/smoke.sh`: health checks, get a token, call `/me`, run one transfer.
3. A CI job that builds both images on the main branch. No push, no deploy.

Rest of M14, still open (the AWS half): ECR push with GitHub OIDC, `docker-compose.aws.yml`, deploy via SSM,
`infra/aws/README.md` with teardown, and a Trivy scan of the images.

Branch: `feature/day25a-containers` (from `master`, which has Day 24 Session B).

Decisions:
- **The main branch is `master` here.** The CI job runs on pushes to the repository's *default* branch
  (`github.event.repository.default_branch`), so it keeps working if the branch is ever renamed to `main`.
- **Base images pinned by digest** (`eclipse-temurin:25-jdk-noble@sha256:4ddc…`, `25-jre-noble@sha256:f3dd…`), like
  the scanners in ADR 0021.
- **The HEALTHCHECK uses bash `/dev/tcp`, not curl.** The JRE image has no curl or wget, and installing one only for
  the check would add a package to patch.
- **New: an optional `jwk-set-uri` in both `SecurityConfig`s.** It wasn't in the brief, but without it the images
  can't serve a single authenticated request locally (§3). The issuer check is unchanged.
- **New: compose profile `apps`.** It runs the two images against the existing local infrastructure, so the smoke
  test can run against the real containers.
- **ADR 0022** records the image decisions. No new Maven dependency.

---

### 1. Dockerfiles (`core-app/Dockerfile`, `risk-aml-service/Dockerfile`, `.dockerignore`)

Build context is `dev/`, since the build needs the parent pom, event-contracts and the wrapper:
`docker build -f core-app/Dockerfile -t trustledger/core-app:local .`

| Part | What it does |
| --- | --- |
| `ARG BUILD_IMAGE` / `RUN_IMAGE` | Temurin 25 JDK and JRE (Ubuntu noble), pinned by `@sha256` digest |
| Build stage | Poms copied first, then sources. `./mvnw -B -ntp -pl <app> -am package -Dmaven.test.skip=true` with `~/.m2` on a BuildKit cache mount, which is never stored in a layer. Tests aren't run here: CI ran them in `verify`, which the images job `needs`. |
| Layer extraction | `java -Djarmode=tools -jar application.jar extract --layers --destination extracted` |
| Run stage | JRE only. Copies `dependencies` → `spring-boot-loader` → `snapshot-dependencies` → `application`, least to most often changed. |
| User | `trustledger`, uid/gid 10001, system user, no shell, no home. `USER 10001:10001` after the copies, so the app files stay root-owned and read-only for the process (`touch /app/x` → Permission denied). |
| Env | `SERVER_PORT` (8080 / 8081; also Spring's `server.port`) and `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC` |
| HEALTHCHECK | Every 15 s, timeout 5 s, start period 120 s, 3 retries. `GET /actuator/health` over `/dev/tcp/127.0.0.1/$SERVER_PORT`; healthy only on `"status":"UP"`. |
| Entry point | `java -jar application.jar`. The extracted jar finds `lib/` through its manifest `Class-Path`. |

`.dockerignore` keeps `.env`/`.env.*` (except `.env.example`), the private HTTP-client env, `target/`, `src/test/`,
`.git`, docs, infra, scripts and load tests out of the context. A secret in the context could end up in a layer or
in the build cache.

**Measured:**

| | core-app | risk-aml-service |
| --- | --- | --- |
| Image size (uncompressed) | 690 MB | 629 MB |
| of which the JRE base | 455 MB | 455 MB |
| `dependencies` layer | 124 MB (221 jars) | |
| `application` layer (our code + event-contracts) | 0.8 MB | |
| Runs as | uid 10001 | uid 10001 |
| Startup in the container | 70 s* | 9 s |

\* The 70 s isn't the image. On startup, core-app's outbox republished thousands of incomplete event publications
left over from the Day 24 load test (`republish-outstanding-events-on-restart`). The log shows them being sent
before Tomcat starts.

**The layering works.** A code change replaces only the 0.8 MB layer. The 124 MB of libraries stay cached locally
and, later, in the registry.

The first build took about 10 minutes, all of it Maven downloads. One attempt failed on a truncated download from
Maven Central ("Premature end of Content-Length"). The retry reused the cache mount and passed. Later builds take
seconds.

### 2. Compose profile `apps` (`infra/docker-compose.yml`)

```
docker compose -f infra/docker-compose.yml --profile apps up -d --build
```

- `core-app` and `risk-aml-service` from their Dockerfiles (`image: trustledger/<app>:local`, `context: ..`).
- They use the same Spring `local` profile as `spring-boot:run`. Only the host names are overridden by environment
  variables: `postgres`, `redis`, `kafka:19092` (the INTERNAL listener), `localstack`, `mailpit`.
- `SPRING_DATASOURCE_URL` uses `$${POSTGRES_DB}`: compose leaves `${POSTGRES_DB}` alone, and Spring fills it in from
  `env_file`.
- `depends_on` waits until the infrastructure is healthy.
- The profile is off by default, so `docker compose up -d` starts exactly what it did before.

### 3. Token validation in containers: optional `jwk-set-uri` (both `SecurityConfig`s)

- **Problem.** The smoke script gets tokens from `http://localhost:8180`, so they carry
  `iss = http://localhost:8180/realms/trustledger`. The app checks `iss` against `issuer-uri`. It also used
  `NimbusJwtDecoder.withIssuerLocation(issuer)` to *fetch the keys* from that URL. Inside a container, `localhost`
  is the container itself, so the first authenticated request would fail.
- **Fix.** A new `static JwtDecoder jwtDecoder(issuer, jwkSetUri, audiences)`. With
  `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` set, the keys come from there. Compose sets
  `http://keycloak:8080/realms/trustledger/protocol/openid-connect/certs`. The validators are the same as before:
  default validators with the issuer, plus the audience check. Without the property nothing changes.
- **`JwkSetUriDecoderTest`** (3 in core-app, the same 3 in risk-aml-service). A JDK `HttpServer` serves a JWKS, and
  the tests sign their own tokens. The issuer is `http://localhost:1/...`, where nothing listens, which proves the
  keys didn't come from the issuer.
  - A token from the configured issuer is accepted, and the JWKS was fetched once.
  - A token with `iss = http://keycloak:8080/...` is **still rejected** (`iss`).
  - A token for another audience is still rejected.
- **Rejected alternatives:**
  - Accepting several issuers would widen what is accepted.
  - `network_mode: host` and `host.docker.internal` are platform-specific.

### 4. `scripts/smoke.sh`

Runs from `dev/` in Git Bash. It stops at the first failure with exit 1. The steps:

1. **Health.** Polls `/actuator/health` of both apps until `"status":"UP"`, for up to `SMOKE_WAIT` (120 s), so it
   can run right after `compose up`. Then it checks that Keycloak serves the realm's OIDC discovery document.
2. **Tokens.** A password grant for the sender and the recipient.
3. **`GET /api/v1/me`** must return 200. It prints only the status and roles, since the body also holds e-mail and
   phone.
4. **One transfer of `SMOKE_AMOUNT` (₹1.00):**
   - 201 `COMPLETED`;
   - the sender's `available` drops by exactly the amount (compared in paise, integer arithmetic);
   - the same `Idempotency-Key` again gives the same transfer ID, `Idempotent-Replayed: true` and no second debit;
   - the recipient can read the transfer.

**Safety:**
- It never prints a token, a password or personal data.
- Plain `http` is accepted only for localhost/127.0.0.1; anything else must be `https`. A password grant over
  http to a remote host would send the test password in clear text. Checked: `TRUSTLEDGER_API=http://example.com`
  → refused, exit 1.
- The password comes from `SMOKE_PASSWORD`, else `TRUSTLEDGER_DEV_USER_PASSWORD` (environment or `.env`).
- Users, client, URLs and amount can be set by environment variables. The header has the perf-user example.

**What the first runs found:**

- **customer1's wallet is frozen** (409 `WALLET_FROZEN`), from the Day 18 AML demo (frozen 2026-11-15). The script
  now prints a hint for this case. I didn't unfreeze your demo data.
- **I froze the perf wallets myself, by accident.** I used perf001/perf002 instead, and they were frozen too. When
  the AML container started, it consumed the Day 24 load-test events that core-app had just republished. Rule R4
  (10+ distinct recipients in 24 h) then froze **all 200 perf wallets** (`wallet.wallet_freezes`: 200 rows at
  2026-11-23 12:xx). `load-tests/README.md` warns about exactly this ("Don't start risk-aml-service"). To finish the
  run, I unfroze **only perf001 and perf002**, through the audited compliance API as officer1 (TOTP), with a reason.
  I made no direct database writes. **The other 198 perf wallets are still frozen**; see "Needs your decision".
- **A bug in the script, fixed.** An idempotent replay returns the response body stored as JSONB, which PostgreSQL
  prints as `"id": "…"` (with a space). The first JSON helper only matched `"id":"…"`. It now allows whitespace
  around the colon.

**Final run, against the two containers:**

```
smoke: ok    core-app health UP (http://localhost:8080)
smoke: ok    risk-aml-service health UP (http://localhost:8081)
smoke: ok    Keycloak realm trustledger discoverable
smoke: ok    tokens for perf001 and perf002
smoke: ok    GET /api/v1/me as perf001: status ACTIVE, roles [CUSTOMER]
smoke: ok    transfer 01a20863-f56d-…: 1.00 INR perf001 -> perf002 COMPLETED, sender debited exactly once
smoke: ok    same Idempotency-Key replayed, no second debit
smoke: ok    perf002 reads the transfer
smoke: PASSED (http://localhost:8080)
```

**The HEALTHCHECK can fail, not only pass.** With `redis` stopped, core-app's `/actuator/health` returned 503, and
the container went `healthy` → `unhealthy` after about 60 s (3 failed checks at 15 s). After restarting Redis it
was `healthy` again within about 35 s.

### 5. CI: `images` job (`../.github/workflows/ci.yml`)

- **When:**
  - `if: github.event_name == 'push' && github.ref == format('refs/heads/{0}', github.event.repository.default_branch)`;
  - `needs: [verify, dependency-scan, secret-scan]`, so the order is test → scan → build, as in the spec.
- **Build:** `docker build -f "$app/Dockerfile" -t "trustledger/$app:$GITHUB_SHA" .` for both apps. Plain Docker,
  no third-party action.
- **Check the images:**
  - `USER` is set, and `docker run --entrypoint id <image> -u` is not 0. The base image alone returns 0, so this
    check can fail.
  - `.Config.Healthcheck` mentions `/actuator/health`.
  - `/app/application.jar` and `/app/lib` exist, i.e. the jar was extracted.
- No `docker login`, no push, no secrets.

The check commands were run locally against both images. In Git Bash the `ls /app/...` check needs
`MSYS_NO_PATHCONV=1`; on the Linux runner it doesn't. The job itself first runs when this branch is merged into
`master`.

### 6. File-based guard: `ContainerImageTest` (10 tests, core-app)

Same idea as `CiSecurityScanTest`: it reads the files, so a regression fails `./mvnw verify` on every branch.
- **Both Dockerfiles:**
  - exactly two stages, `${BUILD_IMAGE}` then `${RUN_IMAGE}`, both Temurin 25 (JDK for the build, JRE for the
    run) and pinned by digest;
  - the wrapper build, the jar copy, the `extract --layers`, the four layer copies in order, and the entry point;
  - exactly one `USER`, `10001:10001`, after the copies and before `ENTRYPOINT`;
  - `SERVER_PORT`, `EXPOSE` and a HEALTHCHECK on `/actuator/health` on the app's own port.
- **`.dockerignore`:** keeps `.env`, `.env.*`, the private HTTP env, `target/` and `.git` out.
- **The CI job:** runs only on the default branch, needs all three jobs, builds both apps, and has no `docker push`,
  no `docker login`, no `aws-actions/` and no `secrets.`.

**Mutation check:** deleting `USER 10001:10001` from `core-app/Dockerfile` failed
`runsAsAFixedNonRootUserAfterTheFilesAreCopied`. The file was restored.

### 7. Docs

- **ADR 0022** (`docs/adr/0022-container-images.md`):
  - the two stages, the digest pinning, the layers, the non-root user;
  - the `/dev/tcp` HEALTHCHECK, and why ECS/Kubernetes should use liveness/readiness instead;
  - the JVM flags, `jwk-set-uri`, and the CI job;
  - alternatives: Buildpacks, Jib, distroless/Alpine, one fat-jar copy, installing curl, host networking, several
    issuers.
- **README:** a "Run the container images and the smoke test" section and the ADR table row.
- **CLAUDE.md** Commands: the `apps` profile and `scripts/smoke.sh`.

### 8. Verification status

`./mvnw verify` **passed** on this branch, the whole reactor (BUILD SUCCESS):

| Module | Unit tests | Integration tests |
| --- | --- | --- |
| core-app | 1336 (+13: `ContainerImageTest` 10, `JwkSetUriDecoderTest` 3) | 812 |
| risk-aml-service | 110 (+3: `JwkSetUriDecoderTest`) | 62 |
| event-contracts | 36 | |

`ModularityTests` and `ArchitectureRulesTest` are green. `CiSecurityScanTest` (8) still passes with the new job:
it uses only `actions/checkout`, and there are still exactly two pinned scanner images.

**Manual checks:**

| Check | Result |
| --- | --- |
| `docker build`, both images | success (core-app after one retry of a truncated Maven Central download) |
| `docker compose --profile apps up`, both containers | `healthy` (risk-aml-service after about 10 s, core-app after about 75 s, see §1) |
| `scripts/smoke.sh` against the containers (perf001 → perf002) | PASSED |
| Same with the defaults (customer1) | stops at the transfer, 409 `WALLET_FROZEN`, with the hint |
| `TRUSTLEDGER_API=http://example.com` | refused, exit 1 |
| Redis stopped | core-app `unhealthy` after about 60 s, `healthy` again about 35 s after restart |
| CI image checks, run locally | uid 10001, HEALTHCHECK present, extracted jar present |

The `images` CI job itself runs for the first time on the merge into `master`.

### Needs your decision

- **198 perf wallets are frozen** (perf003..perf200), by R4 after the load-test backlog reached the AML service.
  Before the next k6 run they need unfreezing, through the compliance API (audited) or by re-seeding a fresh
  database. I can write a small loop like the one used for perf001/perf002 if you want.
- **customer1 is frozen** from the Day 18 AML demo, so `scripts/smoke.sh` with its defaults stops at the transfer.
  Unfreeze it, or keep using perf001 → perf002.
- **About 690 MB images.** Most of it is the Ubuntu JRE base. A distroless or Alpine JRE would cut that, at the cost
  of the bash HEALTHCHECK. Worth deciding together with the Trivy image scan in the AWS session.

### What to learn from it

- **Order layers by how often they change.** Dependencies (124 MB) almost never change; your code (0.8 MB) changes
  on every commit. Put them in separate layers, in that order, and a deploy ships the small one. This is the reason
  for "layered jar".
- **A multi-stage build keeps the toolchain out of production.** The JDK, Maven, sources and `~/.m2` stay in the
  build stage; the run stage gets a JRE and the extracted jar. Less to scan, less to exploit.
- **Non-root also means read-only code.** Running as uid 10001 while the files stay root-owned means a compromised
  process can't rewrite its own jars. Use a numeric `USER`, so platforms can verify it without reading
  `/etc/passwd`.
- **`localhost` means something different in a container.** It is the container itself. The token's issuer is a
  name, and where the keys are fetched is an address; they don't have to be the same URL. Decouple the two
  (`jwk-set-uri`) without loosening the check (`iss` must still match).
- **Keep secrets out of the build context.** `.dockerignore` is a security control. Anything in the context can end
  up in a layer or the build cache, and `docker history` and registries keep it.
- **Prove a health check can go red.** Stopping Redis turned the container unhealthy. Without that test, a check
  that always passes (wrong port, wrong pattern) looks exactly like a working one.
- **A smoke test should check effects, not only status codes.** "201" alone wouldn't have shown a double debit. The
  balance delta and the replay header did. Running it also found real things: a frozen demo wallet and a JSON format
  difference on replays.
- **Starting a consumer replays history.** Bringing up the AML service in front of a backlog of load-test events
  froze 200 wallets in seconds. That is exactly what the service is for, applied to data that wasn't meant for it.
  In production this is why consumers start from a known offset and test traffic is kept away from real rules.
- **Digests, not tags, for anything you run.** The same rule as for the scanners: `25-jre-noble` will point to a
  different image next month; `@sha256:…` never will. Updating it becomes a reviewed commit.
