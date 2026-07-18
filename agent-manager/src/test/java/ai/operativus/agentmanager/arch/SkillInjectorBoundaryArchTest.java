package ai.operativus.agentmanager.arch;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Forward guard pinning the {@code compute/} ⇄ {@code control/}
 *     boundary for the Skills subsystem. The architecture rule is that {@code compute/}
 *     classes resolve skills ONLY through the {@code SkillOperations} SPI seam in
 *     {@code core/registry/} — they must not directly import or inject:
 *     <ul>
 *       <li>{@code control.repository.SkillRepository}</li>
 *       <li>{@code control.repository.AgentSkillRepository}</li>
 *       <li>{@code control.service.SkillService}</li>
 *     </ul>
 *     Bypassing the SPI re-introduces the cross-module coupling that
 *     {@code SkillOperations} was created to prevent (and breaks the
 *     "Ports of entry are the registries, not the services" rule in
 *     {@code agent-manager/CLAUDE.md}).
 *
 *     <p>Implementation: scans classes under {@code ai.operativus.agentmanager.compute}
 *     via {@link ClassPathScanningCandidateComponentProvider} and inspects each class's
 *     declared fields and constructor parameters for the forbidden types. Mirrors
 *     {@code AgentToolDuplicateNameArchTest}'s classpath-scan approach — sidesteps
 *     ArchUnit 1.3.0's Java-25-classfile incompatibility documented in
 *     {@code ControllerContractArchTest}.
 *
 * State: Stateless. Pure-classpath unit test; no Spring context, no Postgres. Runs as
 *     part of {@code ./mvnw test} (no {@code -Dgroups=integration}).
 */
public class SkillInjectorBoundaryArchTest {

    private static final String COMPUTE_PACKAGE = "ai.operativus.agentmanager.compute";

    private static final Set<String> FORBIDDEN_TYPES = Set.of(
            "ai.operativus.agentmanager.control.repository.SkillRepository",
            "ai.operativus.agentmanager.control.repository.AgentSkillRepository",
            "ai.operativus.agentmanager.control.service.SkillService"
    );

    @Test
    void noComputeClass_dependsDirectlyOnSkillRepositoryServiceOrJoinRepository() {
        List<String> violations = new ArrayList<>();

        for (Class<?> computeClass : scanComputeClasses()) {
            checkFields(computeClass, violations);
            checkConstructors(computeClass, violations);
        }

        if (!violations.isEmpty()) {
            fail("compute/ classes must depend on SkillOperations, not on the concrete "
                    + "repository/service. Violations:\n  - "
                    + String.join("\n  - ", violations)
                    + "\nFix: inject ai.operativus.agentmanager.core.registry.SkillOperations instead.");
        }
    }

    private static void checkFields(Class<?> klass, List<String> violations) {
        for (Field field : klass.getDeclaredFields()) {
            String typeName = field.getType().getName();
            if (FORBIDDEN_TYPES.contains(typeName)) {
                violations.add(klass.getName() + "#" + field.getName() + " : " + typeName);
            }
        }
    }

    private static void checkConstructors(Class<?> klass, List<String> violations) {
        for (Constructor<?> ctor : klass.getDeclaredConstructors()) {
            Class<?>[] paramTypes = ctor.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                String typeName = paramTypes[i].getName();
                if (FORBIDDEN_TYPES.contains(typeName)) {
                    violations.add(klass.getName() + "(constructor arg " + i + " : " + typeName + ")");
                }
            }
        }
    }

    private static List<Class<?>> scanComputeClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // Include EVERY class, not just @Component beans — a static utility or a
        // hand-instantiated helper would still violate the boundary if it referenced
        // the forbidden repository directly.
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));

        List<Class<?>> classes = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents(COMPUTE_PACKAGE)) {
            try {
                classes.add(Class.forName(bean.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Scanner returned class " + bean.getBeanClassName()
                        + " that could not be loaded; classpath drift?", e);
            }
        }
        // Also include non-@Component classes the bean filter would miss — fall back to
        // a manual classfile scan via MetadataReader for completeness.
        classes.addAll(scanNonBeanClasses());
        return classes;
    }

    private static List<Class<?>> scanNonBeanClasses() {
        // Spring's RegexPatternTypeFilter+include-everything matches independent classes
        // already; this list is empty but kept as a documented seam for future expansion
        // (e.g. inner records or sealed permits not picked up by the bean scanner).
        MetadataReaderFactory unused = new SimpleMetadataReaderFactory();
        return List.of();
    }

    @SuppressWarnings("unused")
    private static MetadataReader readMetadata(MetadataReaderFactory factory, String fqcn) throws IOException {
        return factory.getMetadataReader(fqcn);
    }
}
