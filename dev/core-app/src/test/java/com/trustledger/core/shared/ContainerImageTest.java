package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M14, ADR 0022: the container images keep their promises. Reads both Dockerfiles, {@code dev/.dockerignore} and the
 * CI workflow (at the git root, like {@code CiSecurityScanTest}). The images job in CI checks the built images
 * (non-root uid, HEALTHCHECK, extracted jar); this test catches a broken Dockerfile before anything is built.
 */
class ContainerImageTest {

    private static final Path DEV = Path.of("..");
    private static final Path WORKFLOW = DEV.resolve(Path.of("..", ".github", "workflows", "ci.yml"));

    private static final Pattern FROM = Pattern.compile("(?m)^FROM (\\S+)(?: AS (\\w+))?$");
    private static final Pattern IMAGE_ARG = Pattern.compile("(?m)^ARG (BUILD_IMAGE|RUN_IMAGE)=(\\S+)$");
    private static final Pattern DIGEST =
            Pattern.compile("^eclipse-temurin:25-(jdk|jre)-[a-z]+@sha256:[0-9a-f]{64}$");
    private static final Pattern USER = Pattern.compile("(?m)^USER (\\S+)$");

    @ParameterizedTest
    @ValueSource(strings = {"core-app", "risk-aml-service"})
    void isAMultiStageBuildFromDigestPinnedTemurin25Images(String app) {
        String dockerfile = dockerfile(app);

        Matcher args = IMAGE_ARG.matcher(dockerfile);
        int pinned = 0;
        while (args.find()) {
            pinned++;
            assertThat(args.group(2)).as(args.group(1)).matches(DIGEST);
            assertThat(args.group(2)).contains(args.group(1).equals("BUILD_IMAGE") ? "-jdk-" : "-jre-");
        }
        assertThat(pinned).as("BUILD_IMAGE and RUN_IMAGE").isEqualTo(2);

        List<String> stages = FROM.matcher(dockerfile).results().map(m -> m.group(1)).toList();
        assertThat(stages).containsExactly("${BUILD_IMAGE}", "${RUN_IMAGE}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"core-app", "risk-aml-service"})
    void buildsWithTheMavenWrapperAndExtractsTheLayers(String app) {
        String dockerfile = dockerfile(app);

        assertThat(dockerfile).contains(
                "./mvnw -B -ntp -pl " + app + " -am package",
                "cp " + app + "/target/" + app + "-*.jar application.jar",
                "java -Djarmode=tools -jar application.jar extract --layers --destination extracted",
                "ENTRYPOINT [\"java\", \"-jar\", \"application.jar\"]");
        // Least to most often changed, so a code change only replaces the last layer.
        assertThat(dockerfile).containsSubsequence(
                "COPY --from=build /workspace/extracted/dependencies/ ./",
                "COPY --from=build /workspace/extracted/spring-boot-loader/ ./",
                "COPY --from=build /workspace/extracted/snapshot-dependencies/ ./",
                "COPY --from=build /workspace/extracted/application/ ./");
    }

    @ParameterizedTest
    @ValueSource(strings = {"core-app", "risk-aml-service"})
    void runsAsAFixedNonRootUserAfterTheFilesAreCopied(String app) {
        String dockerfile = dockerfile(app);

        List<String> users = USER.matcher(dockerfile).results().map(m -> m.group(1)).toList();
        assertThat(users).containsExactly("10001:10001");
        assertThat(dockerfile).contains("useradd --system --uid 10001");
        // After the COPYs (the app files stay root-owned, so read-only for the app) and before ENTRYPOINT.
        assertThat(dockerfile.indexOf("USER 10001:10001"))
                .isGreaterThan(dockerfile.lastIndexOf("COPY --from=build"))
                .isLessThan(dockerfile.indexOf("ENTRYPOINT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"core-app:8080", "risk-aml-service:8081"})
    void checksHealthOnTheActuatorEndpointOfItsOwnPort(String appAndPort) {
        String app = appAndPort.split(":")[0];
        String port = appAndPort.split(":")[1];
        String dockerfile = dockerfile(app);

        assertThat(dockerfile).contains("SERVER_PORT=" + port, "EXPOSE " + port, "HEALTHCHECK ",
                "/dev/tcp/127.0.0.1/${SERVER_PORT}", "GET /actuator/health HTTP/1.1", "\\\"status\\\":\\\"UP\\\"");
    }

    @Test
    void secretsAndBuildOutputStayOutOfTheBuildContext() {
        List<String> ignored = read(DEV.resolve(".dockerignore")).lines().map(String::strip).toList();

        assertThat(ignored).contains(".env", ".env.*", "!.env.example", "http/http-client.private.env.json",
                "**/target/", ".git");
    }

    @Test
    void ciBuildsTheImagesOnlyOnTheDefaultBranchAfterTestsAndScansAndPushesNothing() {
        String workflow = read(WORKFLOW);
        int start = workflow.indexOf("\n  images:\n");
        assertThat(start).as("images job").isNotNegative();
        String job = workflow.substring(start);

        assertThat(job).contains(
                "if: github.event_name == 'push' && github.ref == format('refs/heads/{0}', "
                        + "github.event.repository.default_branch)",
                "needs: [verify, dependency-scan, secret-scan]",
                "docker build -f \"$app/Dockerfile\"",
                "for app in core-app risk-aml-service");
        assertThat(job).doesNotContain("docker push", "docker login", "aws-actions/", "secrets.");
    }

    private static String dockerfile(String app) {
        return read(DEV.resolve(Path.of(app, "Dockerfile")));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path.toAbsolutePath(), e);
        }
    }
}
