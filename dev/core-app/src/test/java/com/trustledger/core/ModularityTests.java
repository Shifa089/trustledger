package com.trustledger.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Architecture guard: fails the build if a module reaches into another module's internals
 * or if modules form a dependency cycle. Pure static analysis, no Spring context.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(TrustLedgerApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentation() {
        // Module diagrams (PlantUML/C4) and canvases -> target/spring-modulith-docs
        new Documenter(modules).writeDocumentation();
    }
}
