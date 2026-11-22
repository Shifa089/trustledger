package com.trustledger.core.audit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.kyc.web.ComplianceKycController;
import com.trustledger.core.payments.web.ComplianceTransferController;
import com.trustledger.core.shared.audit.Audited;
import com.trustledger.core.wallet.web.AdminReconciliationController;
import com.trustledger.core.wallet.web.ComplianceWalletController;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every {@code @Audited} endpoint is wired correctly, checked without starting the application: a known action
 * and entity type, a {@code public} method (so the proxy intercepts it), and an entity ID parameter that exists and
 * is a {@code UUID}. And every compliance and admin action of Day 20 carries the annotation.
 */
class AuditedEndpointsTest {

    private static List<Method> audited;

    @BeforeAll
    static void findAuditedMethods() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.trustledger.core");
        audited = production.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Audited.class))
                .map(JavaMethod::reflect)
                .toList();
    }

    @Test
    void everyComplianceAndAdminActionIsAudited() {
        Map<String, String> actions = audited.stream().collect(Collectors.toMap(
                m -> m.getAnnotation(Audited.class).action(),
                m -> m.getDeclaringClass().getSimpleName() + "." + m.getName()));

        assertThat(actions).containsExactlyInAnyOrderEntriesOf(Map.of(
                "KYC_CASE_VERIFY", "ComplianceKycController.verify",
                "KYC_CASE_APPROVE", "ComplianceKycController.approve",
                "KYC_CASE_REJECT", "ComplianceKycController.reject",
                "KYC_DOCUMENT_VIEW", "ComplianceKycController.document",
                "WALLET_FREEZE", "ComplianceWalletController.freeze",
                "WALLET_UNFREEZE", "ComplianceWalletController.unfreeze",
                "TRANSFER_APPROVE", "ComplianceTransferController.approve",
                "TRANSFER_REJECT", "ComplianceTransferController.reject",
                "RECONCILIATION_RUN", "AdminReconciliationController.run"));
        assertThat(audited).extracting(Method::getDeclaringClass).allMatch(Set.of(ComplianceKycController.class,
                ComplianceWalletController.class, ComplianceTransferController.class,
                AdminReconciliationController.class)::contains);
    }

    @Test
    void everyAnnotationIsWiredCorrectly() {
        for (Method method : audited) {
            Audited annotation = method.getAnnotation(Audited.class);
            String where = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            assertThat(Modifier.isPublic(method.getModifiers())).as("%s is public", where).isTrue();
            assertThatCode(() -> AuditAction.valueOf(annotation.action())).as("action of %s", where)
                    .doesNotThrowAnyException();
            assertThatCode(() -> AuditEntityType.valueOf(annotation.entityType())).as("entity type of %s", where)
                    .doesNotThrowAnyException();
            if (!annotation.entityIdParam().isEmpty()) {
                Object[] args = new Object[method.getParameterCount()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = method.getParameterTypes()[i] == UUID.class ? UUID.randomUUID() : null;
                }
                assertThatCode(() -> AuditedAspect.entityId(method, args, annotation.entityIdParam()))
                        .as("entity ID parameter of %s", where).doesNotThrowAnyException();
            }
        }
    }
}
