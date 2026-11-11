package dev.rashed.dispatch;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    // Fails the build if a module uses another module's internals or modules depend on each other in a cycle.
    @Test
    void modulesRespectBoundaries() {
        ApplicationModules.of(DispatchApplication.class).verify();
    }
}
