package com.trustledger.core.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trustledger.core.audit.domain.AuditActor;
import com.trustledger.core.audit.domain.AuditContext;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestAuditContextProviderTest {

    private final IdentityApi identity = mock(IdentityApi.class);
    private final RequestAuditContextProvider provider = new RequestAuditContextProvider(identity);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    private static JwtAuthenticationToken token(String subject, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject)
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void anOfficerInARequest() {
        UUID officer = UUID.randomUUID();
        when(identity.findUserId("kc-officer")).thenReturn(Optional.of(officer));
        SecurityContextHolder.getContext().setAuthentication(token("kc-officer", "ROLE_COMPLIANCE_OFFICER"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("User-Agent", "TrustLedger-Console/1.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-42");

        AuditContext context = provider.current();

        assertThat(context).isEqualTo(new AuditContext(new AuditActor(officer, "COMPLIANCE_OFFICER"), "203.0.113.7",
                "corr-42", "TrustLedger-Console/1.0"));
    }

    @Test
    void aRequestWithoutUserAgentHasNone() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(provider.current().userAgent()).isNull();
    }

    @Test
    void nobodyOnTheThreadIsTheSystem() {
        assertThat(provider.current()).isEqualTo(AuditContext.SYSTEM);
    }

    @Test
    void aNonJwtAuthenticationIsTheSystem() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("someone", "pw", "ROLE_ADMIN"));

        assertThat(provider.current().actor()).isEqualTo(AuditActor.SYSTEM);
    }

    @Test
    void aSubjectWithoutAProfileIsTheSystem() {
        when(identity.findUserId("kc-ghost")).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(token("kc-ghost", "ROLE_ADMIN"));

        assertThat(provider.current().actor()).isEqualTo(AuditActor.SYSTEM);
    }
}
