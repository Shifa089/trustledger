package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * M13, ADR 0021: the CI security scans stay switched on and strict. The workflow lives at the git root, one level
 * above the Maven project ({@code dev/}), like the Grafana dashboard that {@code GrafanaDashboardTest} reads.
 * <ul>
 * <li>Trivy fails the build on a CRITICAL vulnerability with a fix;</li>
 * <li>gitleaks reads the full history of every branch and fails on a finding;</li>
 * <li>both tools run from images pinned by digest, and no third-party action is used, so nobody can swap the
 * scanner under us by moving a tag (the supply-chain attack these scans are meant to catch);</li>
 * <li>every Trivy exception carries an expiry, and gitleaks keeps its default rules.</li>
 * </ul>
 */
class CiSecurityScanTest {

    private static final Path GIT_ROOT = Path.of("..", "..");
    private static final Path WORKFLOW = GIT_ROOT.resolve(Path.of(".github", "workflows", "ci.yml"));
    private static final Path GITLEAKS_CONFIG = GIT_ROOT.resolve(".gitleaks.toml");
    private static final Path TRIVY_IGNORE = Path.of("..", ".trivyignore");

    private static final Pattern PINNED_IMAGE =
            Pattern.compile("(?m)^\\s+(TRIVY_IMAGE|GITLEAKS_IMAGE): (\\S+)$");
    private static final Pattern DIGEST = Pattern.compile("^[a-z0-9./-]+:[\\w.-]+@sha256:[0-9a-f]{64}$");
    private static final Pattern USES = Pattern.compile("(?m)^\\s+(?:- )?uses: (\\S+)$");

    private final String workflow = read(WORKFLOW);

    @Test
    void hasTheVerifyDependencyAndSecretScanJobs() {
        assertThat(workflow).contains("\n  verify:\n", "\n  dependency-scan:\n", "\n  secret-scan:\n");
    }

    @Test
    void trivyFailsTheBuildOnFixableCriticalVulnerabilities() {
        String gate = step("Fail on CRITICAL vulnerabilities with a fix");
        assertThat(gate).contains("rootfs /scan", "--scanners vuln", "--severity CRITICAL", "--ignore-unfixed",
                "--exit-code 1", "--ignorefile /config/.trivyignore");
        assertThat(gate).doesNotContain("--exit-code 0");
    }

    @Test
    void trivyScansTheShippedJarsOfBothApplications() {
        assertThat(step("Build the application jars"))
                .contains("package -DskipTests", "core-app/target/core-app-*.jar",
                        "risk-aml-service/target/risk-aml-service-*.jar");
    }

    @Test
    void gitleaksScansTheWholeHistoryOfEveryBranch() {
        String job = workflow.substring(workflow.indexOf("\n  secret-scan:\n"));
        assertThat(job).contains("fetch-depth: 0", "--log-opts=--all", "--config /repo/.gitleaks.toml",
                "--redact", "--exit-code 1");
    }

    @Test
    void scannerImagesArePinnedByDigest() {
        Matcher images = PINNED_IMAGE.matcher(workflow);
        int found = 0;
        while (images.find()) {
            found++;
            assertThat(images.group(2)).as(images.group(1)).matches(DIGEST);
        }
        assertThat(found).as("TRIVY_IMAGE and GITLEAKS_IMAGE").isEqualTo(2);
        assertThat(workflow).contains("\"$TRIVY_IMAGE\"", "\"$GITLEAKS_IMAGE\"");
    }

    @Test
    void onlyGitHubsOwnActionsAreUsed() {
        Matcher uses = USES.matcher(workflow);
        int found = 0;
        while (uses.find()) {
            found++;
            assertThat(uses.group(1)).startsWith("actions/");
        }
        assertThat(found).isPositive();
    }

    @Test
    void gitleaksKeepsTheDefaultRules() {
        assertThat(read(GITLEAKS_CONFIG)).contains("[extend]\nuseDefault = true");
    }

    @Test
    void everyTrivyExceptionExpires() {
        List<String> entries = read(TRIVY_IGNORE).lines()
                .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        assertThat(entries).allSatisfy(entry ->
                assertThat(entry).matches("^(CVE|GHSA)-[\\w-]+ exp:\\d{4}-\\d{2}-\\d{2}$"));
    }

    /** The {@code run:} block of the named step, up to the next step. */
    private String step(String name) {
        int start = workflow.indexOf("- name: " + name);
        assertThat(start).as("step %s", name).isNotNegative();
        int end = workflow.indexOf("- name: ", start + 1);
        return workflow.substring(start, end < 0 ? workflow.length() : end);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path.toAbsolutePath(), e);
        }
    }
}
