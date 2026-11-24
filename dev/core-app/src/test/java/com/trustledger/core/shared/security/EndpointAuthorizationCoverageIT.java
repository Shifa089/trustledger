package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every request mapping has an explicit authorisation rule (OWASP API5 BFLA, API9 inventory; M13).
 * <p>
 * The endpoints come from Spring MVC itself ({@link RequestMappingHandlerMapping}), not from a hand-kept list, so a
 * new controller method is found automatically. For each one:
 * <ol>
 * <li>it must appear in {@link #EXPECTED}: a new endpoint fails this test until someone decides who may call it;</li>
 * <li>the URL rules of the production filter chain allow exactly the expected principals. At least one principal is
 * allowed, which proves the endpoint has its own rule and does not just fall through to {@code denyAll()};</li>
 * <li>the handler (method or class) carries {@code @PreAuthorize} granting the same roles, so the two layers agree.
 * The only exception is the public, HMAC-verified Razorpay webhook.</li>
 * </ol>
 * Test-only probe controllers (in {@code test-classes}) and framework handlers (springdoc, error page) are left out.
 * The role-by-role HTTP behaviour (401/403 bodies) is {@link SecurityMatrixIT}'s job.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EndpointAuthorizationCoverageIT {

    enum Caller { ANONYMOUS, NO_ROLES, CUSTOMER, COMPLIANCE_OFFICER, ADMIN, AUDITOR }

    private static final Set<Caller> SIGNED_IN =
            EnumSet.of(Caller.NO_ROLES, Caller.CUSTOMER, Caller.COMPLIANCE_OFFICER, Caller.ADMIN, Caller.AUDITOR);
    private static final Set<Caller> EVERYONE = EnumSet.allOf(Caller.class);
    private static final Set<Caller> CUSTOMER = EnumSet.of(Caller.CUSTOMER);
    private static final Set<Caller> OFFICER = EnumSet.of(Caller.COMPLIANCE_OFFICER);
    private static final Set<Caller> ADMIN = EnumSet.of(Caller.ADMIN);
    private static final Set<Caller> AUDITOR = EnumSet.of(Caller.AUDITOR);

    /** Public on purpose: Razorpay cannot send a JWT; the controller verifies the HMAC signature (FR-P-5). */
    private static final Set<String> PUBLIC_WITHOUT_PRE_AUTHORIZE = Set.of("POST /api/v1/webhooks/razorpay");

    /** Who may call each endpoint (SPEC §5). Path variables other than the version keep their names. */
    private static final Map<String, Set<Caller>> EXPECTED = Map.ofEntries(
            // identity
            Map.entry("GET /api/v1/me", SIGNED_IN),
            Map.entry("GET /api/v1/ping", SIGNED_IN),
            Map.entry("GET /api/v1/me/consents", CUSTOMER),
            Map.entry("POST /api/v1/me/consents", CUSTOMER),
            // kyc
            Map.entry("POST /api/v1/kyc/min", CUSTOMER),
            Map.entry("POST /api/v1/kyc/full", CUSTOMER),
            Map.entry("POST /api/v1/kyc/cases/{id}/documents", CUSTOMER),
            Map.entry("GET /api/v1/kyc/me", CUSTOMER),
            Map.entry("GET /api/v1/compliance/kyc-cases", OFFICER),
            Map.entry("GET /api/v1/compliance/kyc-cases/{id}", OFFICER),
            Map.entry("POST /api/v1/compliance/kyc-cases/{id}/verify", OFFICER),
            Map.entry("POST /api/v1/compliance/kyc-cases/{id}/approve", OFFICER),
            Map.entry("POST /api/v1/compliance/kyc-cases/{id}/reject", OFFICER),
            Map.entry("GET /api/v1/compliance/kyc-cases/{id}/documents/{docId}", OFFICER),
            // wallet and reporting
            Map.entry("GET /api/v1/wallets/me", CUSTOMER),
            Map.entry("GET /api/v1/wallets/me/transactions", CUSTOMER),
            Map.entry("POST /api/v1/compliance/wallets/{id}/freeze", OFFICER),
            Map.entry("POST /api/v1/compliance/wallets/{id}/unfreeze", OFFICER),
            Map.entry("POST /api/v1/admin/reconciliation/run", ADMIN),
            // payments
            Map.entry("POST /api/v1/transfers", CUSTOMER),
            Map.entry("GET /api/v1/transfers/{id}", CUSTOMER),
            Map.entry("POST /api/v1/topups", CUSTOMER),
            Map.entry("GET /api/v1/topups/{id}", CUSTOMER),
            Map.entry("GET /api/v1/compliance/transfers", OFFICER),
            Map.entry("POST /api/v1/compliance/transfers/{id}/approve", OFFICER),
            Map.entry("POST /api/v1/compliance/transfers/{id}/reject", OFFICER),
            Map.entry("POST /api/v1/webhooks/razorpay", EVERYONE),
            // privacy
            Map.entry("POST /api/v1/me/data-export", CUSTOMER),
            Map.entry("POST /api/v1/me/erasure-request", CUSTOMER),
            Map.entry("GET /api/v1/me/data-requests", CUSTOMER),
            // audit
            Map.entry("GET /api/v1/audit/events", AUDITOR),
            Map.entry("GET /api/v1/audit/verify", AUDITOR),
            // ledger
            Map.entry("GET /api/v1/admin/ledger/accounts/{code}/balance", EnumSet.of(Caller.ADMIN, Caller.AUDITOR)));

    private static final Pattern HAS_ROLE = Pattern.compile("^hasRole\\('([A-Z_]+)'\\)$");
    private static final Pattern HAS_ANY_ROLE = Pattern.compile("^hasAnyRole\\(('[A-Z_]+'(?:, *'[A-Z_]+')*)\\)$");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]+}");
    private static final String SAMPLE_ID = "00000000-0000-7000-8000-000000000001";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    FilterChainProxy securityFilterChains;

    private Map<String, HandlerMethod> endpoints;

    @BeforeEach
    void discoverEndpoints() {
        endpoints = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Class<?> type = ClassUtils.getUserClass(handler.getBeanType());
            if (!type.getPackageName().startsWith("com.trustledger.core") || isTestClass(type)) {
                return;
            }
            for (String key : keys(info, handler)) {
                assertThat(endpoints.put(key, handler)).as("duplicate mapping %s", key).isNull();
            }
        });
    }

    @Test
    void everyEndpointIsListedWithItsCallers() {
        assertThat(endpoints).as("discovered endpoints").isNotEmpty();
        assertThat(new TreeSet<>(endpoints.keySet()))
                .as("""
                        A new endpoint needs its own rule in SecurityConfig, a row in SecurityMatrixIT and a row in \
                        EndpointAuthorizationCoverageIT.EXPECTED; a removed one must be taken out of EXPECTED""")
                .containsExactlyElementsOf(new TreeSet<>(EXPECTED.keySet()));
    }

    @Test
    void urlRulesAllowExactlyTheExpectedCallers() {
        List<String> mismatches = new ArrayList<>();
        endpoints.keySet().forEach(key -> {
            String method = key.substring(0, key.indexOf(' '));
            String uri = PATH_VARIABLE.matcher(key.substring(key.indexOf(' ') + 1)).replaceAll(SAMPLE_ID);
            Set<Caller> allowed = EnumSet.noneOf(Caller.class);
            for (Caller caller : Caller.values()) {
                if (isAllowed(method, uri, caller)) {
                    allowed.add(caller);
                }
            }
            if (allowed.isEmpty()) {
                mismatches.add(key + " has no rule of its own: every caller falls through to denyAll()");
            } else if (EXPECTED.containsKey(key) && !allowed.equals(EXPECTED.get(key))) {
                mismatches.add(key + " allows " + allowed + ", expected " + EXPECTED.get(key));
            }
        });
        assertThat(mismatches).isEmpty();
    }

    @Test
    void preAuthorizeGrantsTheSameRolesAsTheUrlRules() {
        List<String> mismatches = new ArrayList<>();
        endpoints.forEach((key, handler) -> {
            PreAuthorize rule = preAuthorize(handler);
            if (rule == null) {
                if (!PUBLIC_WITHOUT_PRE_AUTHORIZE.contains(key)) {
                    mismatches.add(key + " has no @PreAuthorize on the method or its class");
                }
                return;
            }
            Set<Caller> granted = grantedBy(rule.value());
            Set<Caller> expected = EnumSet.copyOf(EXPECTED.getOrDefault(key, EVERYONE));
            expected.remove(Caller.ANONYMOUS);
            if (granted == null) {
                mismatches.add(key + " has an expression this test does not understand: " + rule.value());
            } else if (!granted.equals(expected)) {
                mismatches.add(key + " @PreAuthorize(\"" + rule.value() + "\") grants " + granted
                        + ", the URL rules grant " + expected);
            }
        });
        assertThat(mismatches).isEmpty();
    }

    @Test
    void anUnlistedPathIsDeniedForEveryCaller() {
        for (Caller caller : Caller.values()) {
            assertThat(isAllowed("GET", "/api/v1/not-an-endpoint", caller))
                    .as("%s on an unlisted path", caller).isFalse();
        }
    }

    /**
     * Asks the URL rules of the filter chain that serves this request: the same {@link AuthorizationFilter} a real
     * request passes. (Spring's {@code WebInvocationPrivilegeEvaluator} can't be used here: its stand-in request has
     * no dispatcher type, and our {@code dispatcherTypeMatchers(ERROR)} rule reads it.)
     */
    private boolean isAllowed(String method, String uri, Caller caller) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        SecurityFilterChain chain = securityFilterChains.getFilterChains().stream()
                .filter(candidate -> candidate.matches(request))
                .findFirst().orElseThrow(() -> new AssertionError("no security filter chain serves " + uri));
        AuthorizationFilter filter = chain.getFilters().stream()
                .filter(AuthorizationFilter.class::isInstance).map(AuthorizationFilter.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no authorisation rules for " + uri));
        AuthorizationResult result = filter.getAuthorizationManager().authorize(() -> authentication(caller), request);
        return result != null && result.isGranted();
    }

    /** {@code GET /api/v1/x}; one key per HTTP method and pattern. A mapping without a method is refused. */
    private static List<String> keys(RequestMappingInfo info, HandlerMethod handler) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        assertThat(methods).as("%s must declare its HTTP method", handler).isNotEmpty();
        assertThat(info.getPathPatternsCondition()).as("%s path patterns", handler).isNotNull();
        List<String> keys = new ArrayList<>();
        for (RequestMethod method : methods) {
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                keys.add(method.name() + " " + pattern.replace("{version}", "1"));
            }
        }
        return keys;
    }

    private static boolean isTestClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation().getPath().contains("test-classes");
    }

    private static PreAuthorize preAuthorize(HandlerMethod handler) {
        Method method = handler.getMethod();
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(ClassUtils.getUserClass(handler.getBeanType()),
                        PreAuthorize.class);
    }

    /** The callers an expression grants, for the three forms our controllers use; {@code null} for any other. */
    private static Set<Caller> grantedBy(String expression) {
        if ("isAuthenticated()".equals(expression)) {
            return EnumSet.copyOf(SIGNED_IN);
        }
        Matcher role = HAS_ROLE.matcher(expression);
        if (role.matches()) {
            return EnumSet.of(Caller.valueOf(role.group(1)));
        }
        Matcher anyRole = HAS_ANY_ROLE.matcher(expression);
        if (anyRole.matches()) {
            Set<Caller> callers = EnumSet.noneOf(Caller.class);
            for (String quoted : anyRole.group(1).split(",")) {
                callers.add(Caller.valueOf(quoted.strip().replace("'", "")));
            }
            return callers;
        }
        return null;
    }

    /** A Keycloak-shaped token run through the production {@link JwtRoleConverter}, or an anonymous caller. */
    private static Authentication authentication(Caller caller) {
        if (caller == Caller.ANONYMOUS) {
            return new AnonymousAuthenticationToken("key", "anonymousUser",
                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        }
        List<String> roles = caller == Caller.NO_ROLES ? List.of("default-roles-trustledger")
                : List.of(caller.name(), "default-roles-trustledger");
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-" + caller)
                .claim("realm_access", Map.of("roles", roles)).build();
        return new JwtAuthenticationToken(jwt, new JwtRoleConverter().convert(jwt));
    }
}
