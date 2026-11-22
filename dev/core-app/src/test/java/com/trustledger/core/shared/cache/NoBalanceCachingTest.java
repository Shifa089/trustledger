package com.trustledger.core.shared.cache;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.application.LedgerAccountDirectory;
import com.trustledger.core.ledger.application.LedgerAccountRef;
import com.trustledger.core.wallet.WalletView;
import com.trustledger.core.wallet.application.MyWalletView;
import com.trustledger.core.wallet.application.TierLimitsCatalog;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

/**
 * M11 / ADR 0019: <b>balances are never cached</b>. A cached balance could let money be spent twice or hide a
 * ledger mismatch, so this is checked on every build, not just written in a comment:
 * <ul>
 *   <li>only the two reference-data lookups may be {@code @Cacheable} (a whitelist: a new cached method has to be
 *       added here on purpose);</li>
 *   <li>no method that returns a balance-bearing type, or has "balance" in its name, is cached;</li>
 *   <li>no class-level caching annotation (it would cache every method of the class, balances included);</li>
 *   <li>the cached values themselves carry no balance.</li>
 * </ul>
 * {@code BalanceNotCachedIT} shows the same at runtime against Redis.
 */
class NoBalanceCachingTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.trustledger.core");
    }

    @Test
    void onlyTheReferenceDataLookupsAreCached() {
        methods().that().areAnnotatedWith(Cacheable.class).or().areAnnotatedWith(CachePut.class)
                .should().beDeclaredIn(TierLimitsCatalog.class).orShould().beDeclaredIn(LedgerAccountDirectory.class)
                .because("only tier limits and ledger account lookups are cached (ADR 0019)")
                .check(production);
    }

    @Test
    void nothingThatReturnsABalanceIsCached() {
        noMethods().that().haveRawReturnType(AccountBalance.class)
                .or().haveRawReturnType(WalletView.class)
                .or().haveRawReturnType(MyWalletView.class)
                .or().haveNameMatching("(?i).*balance.*")
                .should().beAnnotatedWith(Cacheable.class)
                .orShould().beAnnotatedWith(CachePut.class)
                .orShould().beAnnotatedWith(Caching.class)
                .because("balances are always read from PostgreSQL (ADR 0019)")
                .check(production);
    }

    @Test
    void noClassLevelCaching() {
        noClasses().should().beAnnotatedWith(Cacheable.class)
                .orShould().beAnnotatedWith(CachePut.class)
                .orShould().beAnnotatedWith(CacheEvict.class)
                .orShould().beAnnotatedWith(Caching.class)
                .because("a class-level annotation would cache every method, balance reads included")
                .check(production);
    }

    @Test
    void cachedValuesCarryNoBalance() {
        assertThat(Arrays.stream(LedgerAccountRef.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(name -> name.toLowerCase().contains("balance"));
        assertThat(CacheNames.ALL).containsExactlyInAnyOrder("wallet-limits", "ledger-accounts");
    }
}
