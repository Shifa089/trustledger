package com.trustledger.core.shared.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint (src/test, never shipped) proving API versioning: served at {@code /api/v1/ping}
 * through the {@code /api/v{version}} prefix from {@link ApiVersioningConfig}.
 */
@RestController
public class PingTestController {

    @GetMapping(path = "/ping", version = "1")
    Map<String, String> ping() {
        return Map.of("status", "pong");
    }
}
