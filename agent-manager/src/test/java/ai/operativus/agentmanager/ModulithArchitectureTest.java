package ai.operativus.agentmanager;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@Disabled("Pending ArchUnit support for Java 25 classfile format")
public class ModulithArchitectureTest {

    @Test
    void verifiesModularStructure() {
        // This test aggressively scans the application context and validates that no
        // unintended cyclic dependencies exist bridging the logical modules.
        ApplicationModules modules = ApplicationModules.of(AgentmanagerApplication.class);
        modules.verify();
    }
}
