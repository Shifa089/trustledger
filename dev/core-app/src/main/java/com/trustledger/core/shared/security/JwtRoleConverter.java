package com.trustledger.core.shared.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak's {@code realm_access.roles} claim to {@code ROLE_*} authorities.
 * <ul>
 * <li>Allowlist: only the four {@link Role}s are mapped. Keycloak's own roles
 * ({@code default-roles-trustledger}, {@code offline_access}, {@code uma_authorization}) and any role
 * added in Keycloak later are ignored until the code knows about them.</li>
 * <li>Case-sensitive: {@code admin} is not {@code ADMIN}.</li>
 * <li>A missing or malformed claim yields no authorities (the request is then authenticated but can
 * only reach endpoints that need no role) instead of an exception.</li>
 * </ul>
 */
public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String REALM_ACCESS = "realm_access";
    static final String ROLES = "roles";

    private static final Set<String> KNOWN = Arrays.stream(Role.values()).map(Role::name)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get(REALM_ACCESS) instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(KNOWN::contains)
                .distinct()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(Role.valueOf(role).authority()))
                .toList();
    }
}
