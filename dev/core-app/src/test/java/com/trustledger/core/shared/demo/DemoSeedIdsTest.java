package com.trustledger.core.shared.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.config.MoneyPropertyConverter;
import com.trustledger.core.shared.perf.PerfSeedIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M16 demo data: the IDs are fixed and distinct, the realm file gives customer1 and customer2 exactly these Keycloak
 * IDs (otherwise their tokens' {@code sub} would not find the seeded wallets), and application-demo.yml binds.
 */
class DemoSeedIdsTest {

    private static final Path REALM_FILE = Path.of("..", "infra", "keycloak", "realm-trustledger.json");
    private static final Path DEMO_YAML = Path.of("src", "main", "resources", "application-demo.yml");

    @Test
    void idsAreDeterministic() {
        assertThat(DemoSeedIds.subject(1)).isEqualTo("de300001-0000-4000-8000-000000000001");
        assertThat(DemoSeedIds.wallet(2)).isEqualTo(UUID.fromString("de300003-0000-4000-8000-000000000002"));
        assertThat(DemoSeedIds.username(1)).isEqualTo("customer1");
        assertThat(DemoSeedIds.seedWallet(1)).isEqualTo(DemoSeedIds.seedWallet(1));
    }

    @Test
    void everyIdIsDistinctAndNoneCollidesWithThePerfData() {
        Set<UUID> ids = new HashSet<>();
        for (String kind : new String[] {DemoSeedIds.SUBJECT_KIND, DemoSeedIds.USER_KIND, DemoSeedIds.WALLET_KIND,
                DemoSeedIds.ACCOUNT_KIND, DemoSeedIds.ENTRY_KIND, DemoSeedIds.DEBIT_LINE_KIND,
                DemoSeedIds.CREDIT_LINE_KIND, DemoSeedIds.MIN_KYC_KIND, DemoSeedIds.FULL_KYC_KIND}) {
            for (int n = 1; n <= DemoSeedIds.CUSTOMERS; n++) {
                assertThat(ids.add(DemoSeedIds.id(kind, n))).isTrue();
            }
        }
        for (int n = 1; n <= DemoSeedIds.CUSTOMERS; n++) {
            assertThat(DemoSeedIds.subject(n)).isNotEqualTo(PerfSeedIds.subject(n));
            assertThat(DemoSeedIds.wallet(n)).isNotEqualTo(PerfSeedIds.wallet(n));
        }
        assertThat(DemoSeedIds.MAKER).isNotEqualTo(DemoSeedIds.CHECKER);
    }

    @Test
    void onlyTwoCustomers() {
        assertThatIllegalArgumentException().isThrownBy(() -> DemoSeedIds.wallet(0));
        assertThatIllegalArgumentException().isThrownBy(() -> DemoSeedIds.subject(3));
    }

    @Test
    void realmFileGivesTheDemoCustomersTheSeededKeycloakIds() throws IOException {
        JsonNode realm = JsonMapper.builder().build().readTree(Files.readString(REALM_FILE));
        Map<String, String> idByUsername = new HashMap<>();
        realm.path("users").forEach(user -> idByUsername.put(user.path("username").asString(),
                user.path("id").isMissingNode() ? null : user.path("id").asString()));

        for (int n = 1; n <= DemoSeedIds.CUSTOMERS; n++) {
            assertThat(idByUsername).containsEntry(DemoSeedIds.username(n), DemoSeedIds.subject(n));
        }
    }

    @Test
    void balancesMustBePositiveRupees() {
        Money one = Money.parse("1.00");
        assertThatIllegalArgumentException().isThrownBy(() -> new DemoSeedProperties(null, one))
                .withMessageContaining("customer1-balance");
        assertThatIllegalArgumentException().isThrownBy(() -> new DemoSeedProperties(one, Money.zero(Money.INR)))
                .withMessageContaining("customer2-balance");
        assertThat(new DemoSeedProperties(one, Money.parse("2.00")).balanceOf(2)).isEqualTo(Money.parse("2.00"));
    }

    @Test
    void demoProfileYamlBindsAndLeavesRoomForTheAmlDemoUnderTheFullCap() throws IOException {
        PropertySource<?> yaml = new YamlPropertySourceLoader()
                .load("application-demo.yml", new FileSystemResource(DEMO_YAML)).getFirst();
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(yaml))
                .withUserConfiguration(BindConfig.class)
                .run(context -> {
                    DemoSeedProperties properties = context.getBean(DemoSeedProperties.class);
                    // docs/aml-demo.md tops customer1 up by 3 x ₹46,000; the FULL cap is ₹2,00,000.
                    assertThat(properties.customer1Balance().plus(Money.parse("138000.00")).amountMinor())
                            .isLessThanOrEqualTo(Money.parse("200000.00").amountMinor());
                    assertThat(properties.customer2Balance().amountMinor()).isPositive();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DemoSeedProperties.class)
    @Import(MoneyPropertyConverter.class)
    static class BindConfig {
    }
}
