package com.trustledger.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @ParameterizedTest
    @EnumSource(Role.class)
    void mapsEachApplicationRoleToRolePrefixedAuthority(Role role) {
        assertThat(authorities(Map.of("roles", List.of(role.name())))).containsExactly("ROLE_" + role.name());
    }

    @Test
    void mapsAllFourRolesTogether() {
        List<String> all = Arrays.stream(Role.values()).map(Role::name).toList();

        assertThat(authorities(Map.of("roles", all)))
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_COMPLIANCE_OFFICER", "ROLE_ADMIN", "ROLE_AUDITOR");
    }

    @Test
    void ignoresKeycloakBuiltInAndUnknownRoles() {
        Map<String, Object> realmAccess = Map.of("roles", List.of("default-roles-trustledger", "offline_access",
                "uma_authorization", "SUPERUSER", "CUSTOMER"));

        assertThat(authorities(realmAccess)).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void isCaseSensitive() {
        assertThat(authorities(Map.of("roles", List.of("admin", "Admin", "ROLE_ADMIN")))).isEmpty();
    }

    @Test
    void removesDuplicates() {
        assertThat(authorities(Map.of("roles", List.of("ADMIN", "ADMIN")))).containsExactly("ROLE_ADMIN");
    }

    @Test
    void missingRealmAccessGivesNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of("sub", "u1")))).isEmpty();
    }

    @Test
    void realmAccessWithoutRolesGivesNoAuthorities() {
        assertThat(authorities(Map.of("other", List.of("ADMIN")))).isEmpty();
    }

    @Test
    void malformedClaimsGiveNoAuthorities() {
        assertThat(converter.convert(jwt(Map.of("realm_access", "ADMIN")))).isEmpty();
        assertThat(authorities(Map.of("roles", "ADMIN"))).isEmpty();
        assertThat(authorities(Map.of("roles", List.of(42, Map.of("name", "ADMIN"))))).isEmpty();
    }

    @Test
    void ignoresClientRolesAndScopes() {
        Jwt jwt = jwt(Map.of(
                "resource_access", Map.of("trustledger-web", Map.of("roles", List.of("ADMIN"))),
                "scope", "openid ADMIN"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private List<String> authorities(Map<String, Object> realmAccess) {
        return converter.convert(jwt(Map.of("realm_access", realmAccess))).stream()
                .map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(300)).subject("user-1");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
