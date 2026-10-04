package com.quipmarket;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build if a module reaches into another module's internal package or modules form a cycle. */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(QuipMarketApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        MODULES.verify();
    }
}
