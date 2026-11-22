package com.trustledger.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditActorTest {

    private final UUID user = UUID.randomUUID();

    @Test
    void theMostPrivilegedRoleIsRecorded() {
        assertThat(AuditActor.of(user, List.of("ROLE_CUSTOMER", "ROLE_ADMIN")).role()).isEqualTo("ADMIN");
        assertThat(AuditActor.of(user, List.of("ROLE_AUDITOR", "ROLE_COMPLIANCE_OFFICER")).role())
                .isEqualTo("COMPLIANCE_OFFICER");
        assertThat(AuditActor.of(user, List.of("ROLE_CUSTOMER", "ROLE_AUDITOR")).role()).isEqualTo("AUDITOR");
        assertThat(AuditActor.of(user, List.of("ROLE_CUSTOMER")))
                .isEqualTo(new AuditActor(user, "CUSTOMER"));
    }

    @Test
    void otherAuthoritiesAreIgnored() {
        assertThat(AuditActor.of(user, List.of("SCOPE_profile", "ROLE_default-roles-trustledger")))
                .isEqualTo(new AuditActor(user, "SYSTEM"));
    }

    @Test
    void anUnknownUserIsTheSystem() {
        assertThat(AuditActor.of(null, List.of("ROLE_ADMIN"))).isEqualTo(AuditActor.SYSTEM);
    }

    @Test
    void onlyTheSystemMayBeAnonymous() {
        assertThatThrownBy(() -> new AuditActor(null, "CUSTOMER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditActor(user, "ROOT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEventCorrelationIdWinsOverTheThreadOne() {
        AuditContext context = new AuditContext(AuditActor.SYSTEM, null, "thread-id");

        assertThat(context.withCorrelationId("event-id").correlationId()).isEqualTo("event-id");
        assertThat(context.withCorrelationId(null).correlationId()).isEqualTo("thread-id");
    }
}
