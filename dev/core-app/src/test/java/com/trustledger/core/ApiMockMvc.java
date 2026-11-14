package com.trustledger.core;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.trustledger.core.identity.web.JitProvisioningFilter;
import com.trustledger.core.shared.ratelimit.RateLimitFilter;
import com.trustledger.core.shared.security.JwtRoleConverter;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Test support: MockMvc with the production filter order (correlation ID, Spring Security, rate limit,
 * JIT provisioning). Built by hand instead of {@code @AutoConfigureMockMvc} so every IT shares one
 * cached application context, and therefore one set of containers.
 */
public final class ApiMockMvc {

    private ApiMockMvc() {
    }

    public static MockMvc create(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter())
                .apply(springSecurity())
                .addFilters(context.getBean(RateLimitFilter.class), context.getBean(JitProvisioningFilter.class))
                .build();
    }

    /** A fresh, never-seen Keycloak subject, so tests do not share users or rate-limit buckets. */
    public static String newSubject() {
        return UUID.randomUUID().toString();
    }

    /**
     * A mock Keycloak access token with the given subject, roles and identity claims, converted by the
     * production {@link JwtRoleConverter}.
     */
    public static RequestPostProcessor token(String subject, String email, String name, String... roles) {
        List<String> realmRoles = new ArrayList<>(List.of(roles));
        realmRoles.add("default-roles-trustledger");
        return jwt().jwt(j -> {
            j.subject(subject).claim("realm_access", Map.of("roles", realmRoles));
            if (email != null) {
                j.claim("email", email);
            }
            if (name != null) {
                j.claim("name", name);
            }
        }).authorities(new JwtRoleConverter());
    }

    public static RequestPostProcessor customer(String subject) {
        return token(subject, "customer.one@example.test", "Test Customer One", "CUSTOMER");
    }
}
