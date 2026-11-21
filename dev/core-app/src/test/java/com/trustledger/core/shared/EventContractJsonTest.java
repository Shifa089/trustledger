package com.trustledger.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.events.AccountFreezeRequestedV1;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import com.trustledger.events.WalletFrozenV1;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON shape of every Kafka contract, locked. A renamed field or a changed type is a breaking change for the
 * consumers (it needs a {@code ...V2} and a new topic), so it must fail here first. Each contract also reads back
 * into an equal record, which is what risk-aml-service will do.
 */
class EventContractJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID EVENT = UUID.fromString("0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");
    private static final Instant AT = Instant.parse("2026-11-21T10:15:30Z");
    private static final UUID WALLET_A = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID WALLET_B = UUID.fromString("0190a1b2-0000-7000-8000-00000000000b");
    private static final UUID REF = UUID.fromString("0190a1b2-0000-7000-8000-0000000000ff");

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of(new TransferCompletedV1(EVENT, AT, WALLET_A, WALLET_B, REF, 4_600_050, "INR"), """
                        {"eventId":"0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b","occurredAt":"2026-11-21T10:15:30Z",
                         "walletId":"0190a1b2-0000-7000-8000-00000000000a",
                         "counterpartyWalletId":"0190a1b2-0000-7000-8000-00000000000b",
                         "transferId":"0190a1b2-0000-7000-8000-0000000000ff","amountMinor":4600050,"currency":"INR"}
                        """),
                Arguments.of(new TopupCapturedV1(EVENT, AT, WALLET_A, REF, 500_000, "INR"), """
                        {"eventId":"0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b","occurredAt":"2026-11-21T10:15:30Z",
                         "walletId":"0190a1b2-0000-7000-8000-00000000000a",
                         "topupId":"0190a1b2-0000-7000-8000-0000000000ff","amountMinor":500000,"currency":"INR"}
                        """),
                Arguments.of(new WalletFrozenV1(EVENT, AT, WALLET_A, "AML"), """
                        {"eventId":"0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b","occurredAt":"2026-11-21T10:15:30Z",
                         "walletId":"0190a1b2-0000-7000-8000-00000000000a","source":"AML"}
                        """),
                Arguments.of(new AccountFreezeRequestedV1(EVENT, AT, WALLET_A, REF, "R2"), """
                        {"eventId":"0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b","occurredAt":"2026-11-21T10:15:30Z",
                         "walletId":"0190a1b2-0000-7000-8000-00000000000a",
                         "caseId":"0190a1b2-0000-7000-8000-0000000000ff","ruleCode":"R2"}
                        """));
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void serializesToTheLockedShape(Record contract, String expectedJson) {
        assertThat(JSON.readTree(JSON.writeValueAsString(contract))).isEqualTo(JSON.readTree(expectedJson));
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void readsBackIntoAnEqualRecord(Record contract, String expectedJson) {
        assertThat(JSON.readValue(expectedJson, contract.getClass())).isEqualTo(contract);
    }
}
