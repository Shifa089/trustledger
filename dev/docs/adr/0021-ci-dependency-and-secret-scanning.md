# ADR 0021: Trivy dependency scanning and gitleaks secret scanning in CI, pinned by image digest

- Status: Accepted
- Date: 2026-11-23
- Spec: docs/SPEC.md §2 (Security: OWASP API Top 10 addressed), §8 M13 ("CI adds Trivy (image and dependency
  scan) and fails on critical vulnerabilities"); CLAUDE.md Security rules ("No secrets in code or Git")

## Context

Two risks can't be found by the tests:

1. **A vulnerable library.** About 200 jars ship inside each Spring Boot fat jar, mostly transitive. A CRITICAL
   CVE in one of them is a hole even if our code is perfect (OWASP API8).
2. **A secret in Git.** `.env` is git-ignored, but a key pasted into a test, a `.http` file or a realm export once
   stays in history after the next commit deletes it. GitHub keeps every commit of every branch that was pushed.

Neither tool is a Maven dependency. They run only in CI, like k6 in M13 Session A, so the build and the
applications don't change. CLAUDE.md still asks for an ADR for new tools, and the pinning choice needs a record.

The scanners themselves are part of the supply chain. Third-party GitHub Actions and image tags can be moved to
point at different code. Scanner actions have been hit by exactly this kind of tag hijacking.

## Decision

**Trivy** (`aquasec/trivy:0.76.2`), `dependency-scan` job:
- **What it scans:** `./mvnw package -DskipTests`, then `trivy rootfs` over the two Spring Boot jars. Scanning the
  shipped jars (`BOOT-INF/lib`) reports the versions that were actually resolved, after BOM management and
  dependency mediation. Reading the poms only reports what Trivy guesses Maven would resolve.
- **Gate:** `--severity CRITICAL --ignore-unfixed --exit-code 1`. The build fails on a CRITICAL that has a fixed
  version. A CRITICAL without a fix can't be acted on by upgrading, so it would only block CI. It still shows in the
  report.
- **Report:** a second run lists HIGH and CRITICAL without failing, and is uploaded as the `trivy-report` artifact.
- **Exceptions:** `dev/.trivyignore`, empty today. An entry needs a reason and an `exp:` date. `CiSecurityScanTest`
  rejects an entry without an expiry.
- **Container images:** scanned in M14, when the Dockerfiles exist.

**gitleaks** (`ghcr.io/gitleaks/gitleaks:v8.30.1`), `secret-scan` job:
- **What it scans:** `actions/checkout` with `fetch-depth: 0`, then `gitleaks git --log-opts=--all`. That covers
  every commit on every branch, not only the diff of a push.
- **Rules:** `/.gitleaks.toml` (at the git root) extends the default rules. Its one allowlist covers the
  hand-written UUIDs sent as `Idempotency-Key` headers in `dev/http/*.http`. Those matched `generic-api-key` 7
  times in the first full-history scan. An idempotency key is a request ID chosen by the client, not a credential.
  The allowlist is narrow: one rule, that header only, a UUID value only, those files only.
- `--redact`, so a real finding is never printed in a public CI log.

**Pinning:**
- Both tools run as `docker run <image>:<tag>@sha256:<digest>`. The digest wins, and nobody can change what it
  points to. The tag is there so people can read which version it is.
- No third-party action is used. Every `uses:` is from GitHub's own `actions/` organisation.
- `CiSecurityScanTest` enforces both points, plus the gate flags and the full-history checkout.

## Alternatives considered

- **`aquasecurity/trivy-action` and `gitleaks/gitleaks-action`.** Less YAML, but more third-party code with access
  to the workflow, which can be moved by a tag. The gitleaks action also needs a licence key for organisation
  repositories.
- **OWASP Dependency-Check Maven plugin.** It runs in the build, but it is a new Maven plugin, slow on first run,
  and it needs an NVD API key to avoid rate limits. Trivy uses a prebuilt database.
- **Fail on HIGH as well.** It would block CI today on lz4-java and snappy-java, which come in through kafka-clients
  and need their versions overridden. We report HIGH and act on it, but the gate stays at the SPEC's level
  (CRITICAL).
- **GitHub secret scanning or push protection.** Useful as well, and free for public repositories, but it can't be
  tested or reviewed in the repository. gitleaks runs the same way locally and in CI.

## Consequences

- A fixable CRITICAL in any shipped library, or a secret anywhere in history, turns CI red. The developer must
  upgrade the library, or rotate the secret and remove it from history. Allowlisting a real secret is never the
  answer.
- Upgrading a scanner means updating the tag and the digest together. `docker pull <image>:<tag>`, then
  `docker image inspect --format '{{index .RepoDigests 0}}'`, gives the digest.
- The Trivy job adds a `package` build and downloads the vulnerability databases, which are cached between runs. It
  runs in parallel with `verify`, so it doesn't lengthen the pipeline. Locally, gitleaks scanned the whole history
  (52 commits, 3.5 MB) in about 30 s.
- Run the scans locally:
  - `docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --config /repo/.gitleaks.toml --log-opts=--all --redact`,
    from the git root;
  - Trivy with the same command as the CI step, against a folder that holds the two jars.
