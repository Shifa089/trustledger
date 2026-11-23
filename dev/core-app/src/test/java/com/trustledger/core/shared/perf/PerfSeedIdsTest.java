package com.trustledger.core.shared.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.trustledger.core.shared.Money;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M13: the seeded IDs are deterministic and distinct, and the two load-test scripts build them the same way as
 * {@link PerfSeedIds}. A mismatch would make every token's {@code sub} unknown (a fresh user without a wallet) or
 * send every transfer to a wallet that doesn't exist.
 */
class PerfSeedIdsTest {

    private static final Path K6_SCRIPT = Path.of("..", "load-tests", "transfers.js");
    private static final Path KEYCLOAK_SCRIPT = Path.of("..", "load-tests", "keycloak-perf-users.sh");

    @Test
    void idsAreDeterministic() {
        assertThat(PerfSeedIds.subject(1)).isEqualTo("0e7f0001-0000-4000-8000-000000000001");
        assertThat(PerfSeedIds.wallet(200)).isEqualTo(UUID.fromString("0e7f0003-0000-4000-8000-000000000200"));
        assertThat(PerfSeedIds.user(42)).isEqualTo(PerfSeedIds.user(42));
        assertThat(PerfSeedIds.username(7)).isEqualTo("perf007");
        assertThat(PerfSeedIds.username(999)).isEqualTo("perf999");
    }

    @Test
    void everyKindAndNumberGivesADifferentId() {
        Set<UUID> ids = new HashSet<>();
        for (String kind : new String[] {PerfSeedIds.SUBJECT_KIND, PerfSeedIds.USER_KIND, PerfSeedIds.WALLET_KIND,
                PerfSeedIds.ACCOUNT_KIND, PerfSeedIds.ENTRY_KIND, PerfSeedIds.DEBIT_LINE_KIND,
                PerfSeedIds.CREDIT_LINE_KIND, PerfSeedIds.MIN_KYC_KIND, PerfSeedIds.FULL_KYC_KIND}) {
            for (int n = 1; n <= 200; n++) {
                assertThat(ids.add(PerfSeedIds.id(kind, n))).isTrue();
            }
        }
        assertThat(PerfSeedIds.MAKER).isNotEqualTo(PerfSeedIds.CHECKER);
    }

    @Test
    void numbersOutsideTheRangeAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> PerfSeedIds.wallet(0));
        assertThatIllegalArgumentException().isThrownBy(() -> PerfSeedIds.subject(PerfSeedIds.MAX_WALLETS + 1));
    }

    @Test
    void k6ScriptUsesTheSameWalletAndSubjectFormat() throws IOException {
        String script = Files.readString(K6_SCRIPT);
        assertThat(script)
                .contains("const SUBJECT_KIND = '" + PerfSeedIds.SUBJECT_KIND + "';")
                .contains("const WALLET_KIND = '" + PerfSeedIds.WALLET_KIND + "';")
                .contains("${WALLET_KIND}-0000-4000-8000-${pad(n, 12)}")
                .contains("username: `perf${pad(n, 3)}`")
                .contains("client_id: 'trustledger-perf'")
                .contains("'Idempotency-Key': crypto.randomUUID()")
                .contains("'http_req_duration{name:transfer}': ['p(99)<300']")
                .contains("'http_req_failed{name:transfer}': ['rate<0.01']");
    }

    @Test
    void keycloakScriptGivesUsersTheSeededSubjects() throws IOException {
        String script = Files.readString(KEYCLOAK_SCRIPT);
        assertThat(script)
                .contains("SUBJECT_KIND=" + PerfSeedIds.SUBJECT_KIND)
                .contains("printf '%s-0000-4000-8000-%012d' \"$SUBJECT_KIND\" \"$1\"")
                .contains("\"username\":\"perf%03d\"")
                .contains("\"realmRoles\":[\"CUSTOMER\"]")
                .contains("\"clientId\":\"trustledger-perf\"");
    }

    @Test
    void propertiesAreValidated() {
        Money balance = Money.parse("100000.00");
        assertThat(new PerfSeedProperties(200, balance).wallets()).isEqualTo(200);
        assertThatIllegalArgumentException().isThrownBy(() -> new PerfSeedProperties(1, balance));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PerfSeedProperties(PerfSeedIds.MAX_WALLETS + 1, balance));
        assertThatIllegalArgumentException().isThrownBy(() -> new PerfSeedProperties(200, Money.zero(Money.INR)));
        assertThatIllegalArgumentException().isThrownBy(() -> new PerfSeedProperties(200, null));
    }
}
