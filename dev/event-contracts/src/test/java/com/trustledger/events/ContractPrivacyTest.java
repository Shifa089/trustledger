package com.trustledger.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Contracts leave the application, so they carry no personal data (CLAUDE.md): IDs and amounts only.
 * <p>
 * Two guards: every field must be on an allowlist (so adding a field is a deliberate change to this test), and no
 * field name may even look like personal data or free text (so the allowlist can't be extended carelessly).
 */
class ContractPrivacyTest {

    static final List<Class<? extends Record>> CONTRACTS = List.of(TransferCompletedV1.class, TopupCapturedV1.class,
            WalletFrozenV1.class, AccountFreezeRequestedV1.class, AmlCaseStatusChangedV1.class);

    private static final Set<String> ALLOWED = Set.of("eventId", "occurredAt", "walletId", "counterpartyWalletId",
            "transferId", "topupId", "caseId", "amountMinor", "currency", "source", "ruleCode", "status", "open");

    private static final Pattern PERSONAL = Pattern.compile(
            "(?i).*(user|name|email|mail|phone|mobile|pan|dob|birth|address|note|reason|detail|ip).*");

    @ParameterizedTest
    @FieldSource("CONTRACTS")
    void fieldsAreOnTheAllowlist(Class<? extends Record> contract) {
        assertThat(fieldNames(contract)).isSubsetOf(ALLOWED);
    }

    @ParameterizedTest
    @FieldSource("CONTRACTS")
    void noFieldLooksLikePersonalData(Class<? extends Record> contract) {
        assertThat(fieldNames(contract)).noneMatch(name -> PERSONAL.matcher(name).matches());
    }

    @ParameterizedTest
    @FieldSource("CONTRACTS")
    void everyContractHasTheCommonEnvelope(Class<? extends Record> contract) {
        assertThat(fieldNames(contract)).startsWith("eventId", "occurredAt", "walletId");
    }

    private static List<String> fieldNames(Class<? extends Record> contract) {
        return Arrays.stream(contract.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
