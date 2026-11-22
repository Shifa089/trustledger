package com.trustledger.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-A-1 "masked before/after": personal data never reaches {@code audit_events}, at any depth. */
class AuditMaskerTest {

    private static AuditEntry entry(Map<String, ?> before, Map<String, ?> after) {
        return new AuditEntry(AuditAction.WALLET_CREATED, AuditEntityType.USER, UUID.randomUUID(), before, after);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> typed(Map<String, ?> map) {
        return (Map<String, Object>) map;
    }

    @Test
    void masksPersonalFieldsWithTheSharedHelpers() {
        Map<String, Object> after = new HashMap<>();
        after.put("pan", "ABCDE1234F");
        after.put("phone", "9876543210");
        after.put("email", "customer.one@example.test");
        after.put("fullName", "Test Customer One");
        after.put("dob", "1990-01-31");
        after.put("address", "1 Test Street, Bengaluru");

        Map<String, Object> masked = typed(AuditMasker.mask(entry(null, after)).after());

        assertThat(masked).containsEntry("pan", "XXXXX1234X")
                .containsEntry("phone", "******3210")
                .containsEntry("email", "c***@example.test")
                .containsEntry("fullName", "T*** C*** O***")
                .containsEntry("dob", "****")
                .containsEntry("address", "****");
    }

    @Test
    void keyMatchingIgnoresCaseAndSeparators() {
        Map<String, Object> masked = typed(AuditMasker.mask(entry(null, Map.of("FULL_NAME", "Test Customer",
                "mobile-number", "9876543210", "Date_Of_Birth", "1990-01-31"))).after());

        assertThat(masked).containsEntry("FULL_NAME", "T*** C***")
                .containsEntry("mobile-number", "******3210")
                .containsEntry("Date_Of_Birth", "****");
    }

    @Test
    void masksNestedMapsAndLists() {
        Map<String, ?> before = Map.of("user", Map.of("email", "someone@example.test", "kycLevel", "MIN"),
                "contacts", List.of(Map.of("phone", "9876543210")));

        Map<String, Object> masked = typed(AuditMasker.mask(entry(before, null)).before());

        assertThat(masked.get("user")).isEqualTo(Map.of("email", "s***@example.test", "kycLevel", "MIN"));
        assertThat(masked.get("contacts")).isEqualTo(List.of(Map.of("phone", "******3210")));
    }

    @Test
    void aPersonalKeyHoldingAStructureIsFullyMasked() {
        Map<String, Object> masked = typed(AuditMasker.mask(entry(null, Map.of("address", Map.of("city", "Bengaluru"),
                "name", List.of("Test", "Customer")))).after());

        assertThat(masked).containsEntry("address", "****");
        assertThat(masked.get("name")).isEqualTo(List.of("T***", "C***"));
    }

    @Test
    void leavesIdsAmountsAndStatusesAlone() {
        String wallet = UUID.randomUUID().toString();
        Map<String, ?> after = Map.of("walletId", wallet, "amount", "1500.50", "status", "FROZEN", "count", 3L);

        assertThat(AuditMasker.mask(entry(null, after)).after()).isEqualTo(after);
    }

    @Test
    void malformedPersonalDataIsFullyMaskedAndNullsStayNull() {
        Map<String, Object> after = new HashMap<>();
        after.put("pan", "not-a-pan");
        after.put("email", null);

        assertThat(typed(AuditMasker.mask(entry(null, after)).after())).containsEntry("pan", "****")
                .containsEntry("email", null);
    }

    @Test
    void keepsActionEntityAndAbsentStates() {
        AuditEntry entry = entry(null, null);
        AuditEntry masked = AuditMasker.mask(entry);

        assertThat(masked.action()).isEqualTo(entry.action());
        assertThat(masked.entityType()).isEqualTo(entry.entityType());
        assertThat(masked.entityId()).isEqualTo(entry.entityId());
        assertThat(masked.before()).isNull();
        assertThat(masked.after()).isNull();
    }
}
