package com.trustledger.core;

import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Real Keycloak (same image as infra/docker-compose.yml) importing the real
 * {@code infra/keycloak/realm-trustledger.json}, so tests prove the realm file itself works.
 * The placeholders in the realm file are filled from these fixed, obviously fake, test-only values.
 */
public class KeycloakContainer extends GenericContainer<KeycloakContainer> {

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.8.2";
    public static final String REALM = "trustledger";
    public static final String TEST_USER_PASSWORD = "test-only-Passw0rd!";
    /** Raw TOTP secret as Keycloak stores it (HMAC key = its UTF-8 bytes). Test-only. */
    public static final String TEST_TOTP_SECRET = "test-only-totp-secret-0123";

    private static final int HTTP_PORT = 8080;
    private static final int MANAGEMENT_PORT = 9000;
    /** Maven runs core-app tests with the module directory as working directory. */
    private static final Path REALM_FILE = Path.of("..", "infra", "keycloak", "realm-trustledger.json");

    public KeycloakContainer() {
        super(DockerImageName.parse(IMAGE));
        withCommand("start-dev", "--import-realm");
        withEnv("KC_HEALTH_ENABLED", "true");
        withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
        withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "test-only-admin");
        withEnv("TRUSTLEDGER_DEV_USER_PASSWORD", TEST_USER_PASSWORD);
        withEnv("TRUSTLEDGER_DEV_TOTP_SECRET", TEST_TOTP_SECRET);
        withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE),
                "/opt/keycloak/data/import/realm-trustledger.json");
        withExposedPorts(HTTP_PORT, MANAGEMENT_PORT);
        waitingFor(Wait.forHttp("/health/ready").forPort(MANAGEMENT_PORT)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    public String baseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    public String issuerUri() {
        return baseUrl() + "/realms/" + REALM;
    }

    public String tokenEndpoint() {
        return issuerUri() + "/protocol/openid-connect/token";
    }
}
