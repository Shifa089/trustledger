# ADR 0022: Container images: multi-stage Dockerfiles, layered jar, non-root, built in CI without pushing

- Status: Accepted
- Date: 2026-11-23
- Spec: docs/SPEC.md §8 M14 ("Multi-stage Dockerfiles (non-root user, layered JAR) for both apps"; "test → scan →
  build images"), §10 (local services: core-app and risk-aml-service "built locally")

## Context

M14 deploys both apps as containers. This first session builds the images and checks them in CI, without AWS: no
registry push, no deploy. Choices that are hard to change later and need a record:

- which base images, and how they are pinned;
- how the jar goes into the image (one fat jar, or layers);
- which user the JVM runs as, and how health is checked;
- how a containerised app validates tokens whose issuer (`http://localhost:8180/...`) it cannot reach.

No Maven dependency is added. Docker and the Temurin base images are build and runtime tools.

## Decision

**Two stages, build context `dev/`.**
- Stage 1 (`eclipse-temurin:25-jdk-noble`) runs `./mvnw -pl <app> -am package -Dmaven.test.skip=true`, with
  `~/.m2` on a BuildKit cache mount (kept between local builds, never in a layer). Tests are not run here:
  CI's images job `needs` the `verify` job, which ran them.
- Stage 2 (`eclipse-temurin:25-jre-noble`) gets only the extracted jar. No JDK, Maven or sources.
- Both base images are pinned by `@sha256` digest, like the scanners in ADR 0021. A tag can be moved to other
  content; a digest can't. Updating = pull the tag, take the new digest, rebuild, run the CI scans.

**Layered jar.** `java -Djarmode=tools -jar application.jar extract --layers` (Boot 3.3+ tools mode) splits the jar
into `dependencies` (124 MB for core-app), `spring-boot-loader`, `snapshot-dependencies` and `application`
(0.8 MB, our code and event-contracts as a reactor module). They are copied least to most often changed, so a code
change rebuilds and ships only the last layer. The extracted `application.jar` keeps a manifest `Class-Path` to
`lib/`, so the entry point stays `java -jar application.jar`.

**Non-root.** A system user `trustledger` with a fixed uid/gid 10001, no shell, no home. `USER 10001:10001` after the
copies, so the application files stay root-owned: the process can read its code but not change it. A numeric
`USER` also lets Kubernetes `runAsNonRoot` verify it without looking up `/etc/passwd`.

**HEALTHCHECK on `/actuator/health`.** The JRE image has neither curl nor wget. Installing one only for the check
would add a package to patch and to scan. Instead, plain HTTP over bash's `/dev/tcp`, the same trick as the Keycloak
check in `infra/docker-compose.yml`. It is healthy only on `"status":"UP"`. That status covers the database, Redis,
mail and the other indicators: with Redis stopped, core-app turned `unhealthy` after about a minute (3 failed
checks at 15 s) and recovered on its own. `SERVER_PORT` is set in the image and is also Spring's `server.port`, so
the app and the check can't disagree on the port. A deployment platform with its own probes (ECS, Kubernetes) should
use `/actuator/health/liveness` and `/readiness` instead. The image check is for Docker and Compose.

**JVM settings** in `JAVA_TOOL_OPTIONS`: `-XX:MaxRAMPercentage=75` (heap from the container limit),
`-XX:+ExitOnOutOfMemoryError` (crash and restart instead of running on in a broken state), `-Duser.timezone=UTC`
(as in every other JVM of the project). Overridable per environment.

**Token validation in containers: optional `jwk-set-uri`.** Tokens requested from the host carry
`iss = http://localhost:8180/realms/trustledger`. Inside a container, `localhost` is the container, so OIDC discovery
from the issuer fails. `SecurityConfig` (both apps) now reads keys from
`spring.security.oauth2.resourceserver.jwt.jwk-set-uri` when it is set (`http://keycloak:8080/.../certs` in compose).
The issuer check is unchanged: `iss` must still equal `issuer-uri`. Only where the keys are read from changes, never
which tokens are accepted (`JwkSetUriDecoderTest`: a token from `http://keycloak:8080/...` is still rejected).
Without the property, behaviour is exactly as before.

**CI `images` job.**
- Runs only on a push to the default branch (`github.event.repository.default_branch`, `master` here), after
  `verify`, `dependency-scan` and `secret-scan`.
- Builds both images with plain `docker build`, no third-party action.
- Checks that each image runs as a non-zero uid, has a HEALTHCHECK on `/actuator/health`, and contains the
  extracted jar.
- No `docker login`, no push, no secrets.
- `ContainerImageTest` guards the Dockerfiles, `.dockerignore` and this job as text, so a regression fails
  `./mvnw verify` on every branch, not only on `master`.

## Alternatives considered

- **Cloud Native Buildpacks (`spring-boot:build-image`).** No Dockerfile to maintain, and layered and non-root by
  default. But the result is less transparent (an interview question is "show me your Dockerfile"), it needs to pull
  builder images at build time, and the spec asks for multi-stage Dockerfiles.
- **Jib.** Daemonless and fast, but it's a new Maven plugin (a dependency), and it hides the image layout in plugin
  configuration.
- **Distroless or Alpine JRE.** Smaller and fewer packages. Distroless has no shell, so the HEALTHCHECK would need a
  Java helper, and Alpine (musl) Temurin 25 builds lag behind. Worth reconsidering when the images are scanned
  (Trivy image scan, AWS half of M14).
- **One fat jar `COPY`.** Simpler, but every code change ships about 125 MB again.
- **Installing curl for the HEALTHCHECK.** One line, but a permanent extra package in a regulated app's image.
- **`network_mode: host` or `host.docker.internal` for the issuer.** Docker Desktop host networking is opt-in and
  platform-specific, and `host.docker.internal` doesn't resolve the same way on a Linux runner.
- **Making the apps accept several issuers.** That widens what is accepted. Changing only the key source doesn't.

## Consequences

- `docker compose -f infra/docker-compose.yml --profile apps up -d --build` runs both images against the local
  infrastructure, and `scripts/smoke.sh` checks them: health, token, `/me`, one transfer and its idempotent replay.
- First build: about 10 minutes (all Maven downloads). Later local builds take seconds, thanks to the cache mount
  and layer cache. In CI each run builds from scratch; acceptable while it runs only on `master`.
- Image sizes (uncompressed): core-app about 690 MB, risk-aml-service about 630 MB, of which about 455 MB is the
  JRE base image.
- Base-image digests need bumping by hand, like the scanner digests. The images are not yet scanned by Trivy.
  That comes with the registry push.
- The `jwk-set-uri` option is also useful on AWS if Keycloak is reached internally under another name than its
  public issuer URL.
