# Changes

## DAY1 — Milestone M0: repository skeleton and local infrastructure

**Goal:** a multi-module Maven repo that builds and tests green (locally and in CI), and a
local stack that starts with one command. No business code yet.

Branch: `feature/m0-skeleton`

---

### 1. How to verify Day 1

Run these from `dev/` (the Maven project root; the git root is one level up).

| # | Command | Why it is necessary |
|---|---|---|
| 1 | `cp .env.example .env` (then edit the passwords) | Docker Compose and the `local` profile read credentials from `.env`. It is git-ignored so secrets never reach GitHub. |
| 2 | `docker compose -f infra/docker-compose.yml up -d` | Starts PostgreSQL, Redis, Kafka, Keycloak, Mailpit and LocalStack. |
| 3 | `docker compose -f infra/docker-compose.yml ps` | Every service must show **(healthy)**, not just "Up". Healthchecks prove the service accepts work (Postgres takes connections, Keycloak finished the realm import), not only that the process started. |
| 4 | `./mvnw verify` | The full build: compiles all modules, runs unit tests (Surefire), integration tests on Testcontainers (Failsafe), the Spring Modulith architecture check, and writes JaCoCo coverage. This is the same command CI runs, so green here means green in CI. |
| 5 | `./mvnw verify -DskipUnitTests` | Runs only the `*IT` integration tests. Proves the unit/integration split works and is handy when only infrastructure code changed. |
| 6 | `./mvnw install -DskipTests` | Needed once before any `-pl <module>` run (and after changing `event-contracts`): `-pl` builds one module only and looks up its sibling `event-contracts` in `~/.m2`. |
| 7 | `./mvnw -pl core-app spring-boot:run -Dspring-boot.run.profiles=local` then open `http://localhost:8080/actuator/health` | Proves core-app starts against the compose Postgres/Redis using the `local` profile. Expect `200`. |
| 8 | `./mvnw -pl risk-aml-service spring-boot:run -Dspring-boot.run.profiles=local` then `http://localhost:8081/actuator/health` | Same check for the AML service on port 8081. |
| 9 | `docker compose -f infra/docker-compose.yml --profile obs up -d lgtm` then `http://localhost:3000` | Proves the optional observability profile (Grafana LGTM) starts. Stop it afterwards to save RAM. |
| 10 | Push the branch and check the **CI** run on GitHub | Proves the build is reproducible on a clean Linux machine, not just this laptop. |

Kafka can additionally be smoke-tested through both listeners (on Git Bash, prefix with
`MSYS_NO_PATHCONV=1` so `/opt/...` is not rewritten to a Windows path):

```bash
docker compose -f infra/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic m0.smoke
echo hello | docker compose -f infra/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic m0.smoke
docker compose -f infra/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic m0.smoke --from-beginning --max-messages 1
docker compose -f infra/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic m0.smoke
```

**Results on Day 1:** all 6 default services healthy, `obs` profile healthy, `./mvnw verify`
BUILD SUCCESS (ModularityTests 2/2, TrustLedgerApplicationIT 1/1, RiskAmlApplicationIT 1/1),
both apps return `200` on `/actuator/health`, Kafka message round-trip OK.

---

### 2. What changed and why

#### Session A — Maven multi-module skeleton

| File | Change | Why |
|---|---|---|
| `pom.xml` | Rewritten as parent POM `trustledger-parent` (packaging `pom`), Spring Boot 4.1.2 parent, Java 25, modules `event-contracts`, `core-app`, `risk-aml-service`; imports Spring Modulith BOM 2.1.2 and Testcontainers BOM 2.0.5 | One place to manage versions for all modules; BOMs keep related libraries on compatible versions. |
| `src/`, `HELP.md` | Deleted | Leftovers of the single-module start.spring.io project (`com.trustledger.dev`). |
| `docs/TrustLedger Implementation Guide…md` → `docs/SPEC.md` | Renamed | CLAUDE.md and every milestone prompt refer to `docs/SPEC.md`. |
| `mvnw` | Marked executable in git (`100755`) | Without the executable bit, `./mvnw` fails on Linux CI runners. |
| `.gitignore` | Rewritten: Maven `target/`, IntelliJ, VS Code, OS files, `.env` (keeping `.env.example`) | Keep build output and secrets out of Git. |
| `.gitattributes` | `* text=auto`, `mvnw` and `*.sh` LF, `*.cmd` CRLF | Shell scripts with Windows line endings break on Linux (`/bin/sh^M`). |
| `.env.example` | New: Postgres, Redis, Keycloak admin variables with placeholder values | Documents which secrets are needed without committing real ones. |
| `event-contracts/pom.xml`, `…/com/trustledger/events/package-info.java` | New plain-jar module | Shared, versioned Kafka event records for both apps (filled from M8). No Spring dependency so both sides can use it freely. |
| `core-app/pom.xml` | New: webmvc, validation, data-jpa, flyway (+ postgresql dialect), postgresql, data-redis, security, oauth2-resource-server, actuator, kafka, modulith starter; test: webmvc/data-jpa/security tests, modulith test + docs, Testcontainers | Dependencies from SPEC §8 M0 step 2. `data-redis` is required for the Redis `@ServiceConnection` (Redis is in the stack for rate limits and idempotency). |
| `core-app/…/core/TrustLedgerApplication.java` | New main class | Entry point of the modular monolith (`com.trustledger.core`). |
| `risk-aml-service/pom.xml`, `…/aml/RiskAmlApplication.java` | New app (`com.trustledger.aml`) | Separate AML microservice; no Redis and no Modulith (it is not modular). |
| `*/src/main/resources/application.yml` | App name, virtual threads on, `open-in-view: false`, `ddl-auto: validate`, `hibernate.jdbc.time_zone: UTC`; AML on port 8081 | Conventions from SPEC §6: virtual threads for throughput, no lazy loading in views, schema owned by Flyway (Hibernate only validates), UTC in the DB. |
| `*/src/main/resources/application-local.yml` | `local` profile: datasource (and Redis for core-app) pointing at the compose services; imports `.env` | Run the apps against Docker Compose without hard-coding credentials. |
| `*/src/test/…/TestcontainersConfiguration.java` | New: `@ServiceConnection` PostgreSQL 17 (+ Redis for core-app) | One shared container setup; Spring Boot derives connection properties automatically and the test context cache reuses containers across test classes. |
| `*/src/test/…/Test*Application.java` | New | Run an app from the IDE against throwaway containers instead of Compose. |
| `infra/docker-compose.yml` | New: `postgres:17`, `redis:8-alpine` with healthchecks and named volumes; credentials via `env_file: ../.env` | One-command local infrastructure. `$$VAR` lets the container expand the variable, so passwords never appear in the compose file. |

**Fix found while testing — JVM time zone.** On Windows the JVM default zone was the legacy
alias `Asia/Calcutta`; the PostgreSQL driver sends it on connect and `postgres:17` rejects it
(`FATAL: invalid value for parameter "TimeZone"`). The parent POM now runs every JVM with
`-Duser.timezone=UTC` (tests via `argLine`, `spring-boot:run` via `jvmArguments`). This also
matches the spec: `Instant` in code, UTC in the database.

#### Session B — Full local stack, architecture test, coverage, CI, ADR

| File | Change | Why |
|---|---|---|
| `infra/docker-compose.yml` | Added Kafka 4.4.0 (KRaft single node, `INTERNAL://kafka:19092` for containers and `EXTERNAL://localhost:9092` for the host), Keycloak 26.8.2 (`start-dev --import-realm`, port 8180), Mailpit v1.33.1 (8025/1025), LocalStack 4.14 (s3, kms, 4566), and `grafana/otel-lgtm` 0.40.0 under profile `obs`. All images pinned, all with healthchecks | Full local stack from SPEC §10. Kafka needs two listeners because clients reconnect to the *advertised* address, which differs inside and outside Docker. LocalStack is pinned to the last semver community release (no auth token needed). The `obs` placeholder gets configured on Day 23 (M12). |
| `infra/keycloak/realm-trustledger.json` | New minimal realm `trustledger` | Imported at Keycloak startup; roles and clients are added in M2. |
| `.env.example` | Added `KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD` | Keycloak admin login without hard-coded credentials. |
| `pom.xml` | Surefire runs `*Test`/`*Tests` and honours `-DskipUnitTests`; Failsafe runs `*IT`; JaCoCo 0.8.16 `prepare-agent` + `report` on `verify` | Fast unit tests and slower container tests are separated. JaCoCo's agent is added to `argLine`, which both plugins use, so one report covers unit and integration tests (and the UTC flag is preserved). |
| `core-app/…/ModularityTests.java` | New: `ApplicationModules.of(TrustLedgerApplication.class).verify()` + `Documenter` | Fails the build if a module reaches into another module's internals or modules form a cycle; writes module diagrams to `target/spring-modulith-docs`. |
| `*ApplicationTests.java` → `*ApplicationIT.java` | Renamed (core-app and AML) | They start Testcontainers, so they are integration tests and belong to Failsafe. |
| `../.github/workflows/ci.yml` (git root) | New: on push and PR, Temurin 25 with Maven cache, `./mvnw -B -ntp verify`, upload JaCoCo reports (and test reports on failure). Runs with `working-directory: dev` | GitHub only reads workflows from the git root, while the Maven project lives in `dev/`. Docker is preinstalled on ubuntu runners, so Testcontainers works. |
| `docs/adr/0001-modular-monolith.md` | New ADR | Records why the core is a modular monolith and AML a separate service (SPEC §3), with alternatives and trade-offs, for reviewers and interviews. |
| `CLAUDE.md` | Added the "integration tests only" and "install once before -pl" commands | Commands discovered while verifying Day 1. |
| `CHANGES.md` | New (this file) | Day-by-day record of what changed, why, and how to verify it. |

---

### 3. Machine setup done on Day 1 (not in Git)

- **WSL 2 installed** — Docker Desktop on Windows needs it; without it Testcontainers found no Docker environment.
- **Windows dynamic TCP port range reset to the default 49152–65535** (admin PowerShell:
  `netsh int ipv4 set dynamic tcp start=49152 num=16384`, same for ipv6, then
  `net stop winnat` / `net start winnat`). The range started at 1024, so Hyper-V reserved
  9066–9165 and Kafka could not bind port 9092. With the default range, our service ports
  can no longer be reserved.
- **Local `.env`** created from `.env.example` (git-ignored).

### 4. Deferred

- Keycloak roles, clients and test users: M2.
- Flyway migrations and per-module schemas: M1 onwards.
- Observability configuration (dashboards, OTLP export from the apps): Day 23 (M12).
- README skeleton: not done yet; suggested for the next session.

---

Day 2 (M1 shared foundation) is in [day2_changes.md](day2_changes.md).

Day 3 (M2 part 1: Keycloak realm, API security, auth.http) is in [day3_implementation.md](day3_implementation.md).
