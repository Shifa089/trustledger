package com.trustledger.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the e-mails the test Mailpit received, through its HTTP API (FR-N-1 tests). Each test uses its own
 * recipient addresses, so tests never see each other's mail and nothing needs deleting.
 */
public final class MailpitClient {

    public static final String IMAGE = "axllent/mailpit:v1.33.1";
    public static final int SMTP_PORT = 1025;
    public static final int HTTP_PORT = 8025;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One received e-mail: recipient, sender, subject and plain-text body. */
    public record Mail(String id, String from, List<String> to, String subject, String text) {
    }

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public MailpitClient(GenericContainer<?> container) {
        this.container = container;
    }

    /** Every message addressed to {@code address}, with its body. */
    public List<Mail> messagesTo(String address) {
        String query = URLEncoder.encode("to:\"" + address + "\"", StandardCharsets.UTF_8);
        JsonNode found = get("/api/v1/search?query=" + query);
        List<Mail> mails = new ArrayList<>();
        for (JsonNode summary : found.path("messages")) {
            mails.add(message(summary.path("ID").asString()));
        }
        return mails;
    }

    /** Pauses the container: SMTP connections hang, as with an unreachable mail server. */
    public void pause() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    public void unpause() {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }

    private Mail message(String id) {
        JsonNode message = get("/api/v1/message/" + id);
        List<String> to = new ArrayList<>();
        for (JsonNode recipient : message.path("To")) {
            to.add(recipient.path("Address").asString());
        }
        return new Mail(id, message.path("From").path("Address").asString(), to, message.path("Subject").asString(),
                message.path("Text").asString());
    }

    private JsonNode get(String path) {
        URI uri = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT) + path);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mailpit " + path + " returned " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Mailpit " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
