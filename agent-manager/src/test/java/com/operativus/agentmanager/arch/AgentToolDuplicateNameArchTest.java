package com.operativus.agentmanager.arch;

import com.operativus.agentmanager.compute.tools.AgentToolComponent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Build-time forward guard against duplicate {@link Tool} names
 *   across {@link AgentToolComponent} beans. Two beans declaring {@code @Tool(name = "x")}
 *   on different methods cause Spring AI's {@code MethodToolCallbackProvider} to fail at
 *   composite construction with {@code IllegalStateException: Multiple tools with the same
 *   name ...}. {@code ToolNameDuplicationTest} pins that boot-time behavior;
 *   <b>this</b> ArchTest catches the collision at {@code ./mvnw test} time so the
 *   regression never reaches a boot attempt.
 *
 * State: Stateless. Pure-classpath reflection — no Spring context, no DB. Runs as part
 *   of {@code ./mvnw test} (no {@code -Dgroups=integration} required).
 *
 * Why classpath scanning instead of ArchUnit:
 *   Mirrors {@link ControllerContractArchTest}'s design — ArchUnit 1.3.0 cannot read
 *   Java 25 classfiles. Reflection on the scanned classes sidesteps the classfile-format
 *   dependency entirely.
 *
 * <p>The effective tool name is the value of {@code @Tool#name()} when non-empty, otherwise
 * the declaring method's simple name. This mirrors {@code ToolConfig.warnOnReservedPrefixCollision}'s
 * resolution rule and is the same name Spring AI exposes to the LLM.</p>
 *
 * <p>No allowlist intentionally: the codebase has no duplicates today
 * ({@code ToolDiscoveryRuntimeTest.noDuplicateToolNames} proves it at runtime), so the
 * forward guard starts with a zero-violation baseline. If a legitimate duplicate ever
 * arises (it shouldn't — the LLM cannot disambiguate same-named callbacks), add a tagged
 * allowlist in a follow-up PR.</p>
 */
public class AgentToolDuplicateNameArchTest {

    private static final String SCAN_BASE_PACKAGE = "com.operativus.agentmanager";

    @Test
    void noTwoAgentToolComponentsDeclareTheSameToolName() {
        // name → list of "ClassName#methodName" sites that declared it
        Map<String, List<String>> nameToSites = new TreeMap<>();

        for (Class<?> toolBean : findAgentToolComponentClasses()) {
            for (Method method : toolBean.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) {
                    continue;
                }
                String effectiveName = annotation.name().isEmpty()
                        ? method.getName()
                        : annotation.name();
                String site = toolBean.getName() + "#" + method.getName();
                nameToSites.computeIfAbsent(effectiveName, k -> new ArrayList<>()).add(site);
            }
        }

        Map<String, List<String>> duplicates = new LinkedHashMap<>();
        for (var entry : nameToSites.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }

        if (!duplicates.isEmpty()) {
            String report = duplicates.entrySet().stream()
                    .map(e -> "  - \"%s\" declared by:\n      * %s".formatted(
                            e.getKey(), String.join("\n      * ", e.getValue())))
                    .collect(Collectors.joining("\n"));
            fail("""
                    Multiple @AgentToolComponent beans declare the same @Tool name. Spring AI's
                    MethodToolCallbackProvider will throw IllegalStateException at boot — and even
                    if it did not, the LLM cannot disambiguate two callbacks with the same name.
                    Rename one of the methods (or remove the explicit @Tool(name = "...") so the
                    method name disambiguates them).

                    %d duplicate name(s):
                    %s""".formatted(duplicates.size(), report));
        }
    }

    private static List<Class<?>> findAgentToolComponentClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentToolComponent.class));

        List<Class<?>> classes = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(SCAN_BASE_PACKAGE)) {
            String fqcn = bd.getBeanClassName();
            if (fqcn == null) {
                continue;
            }
            Class<?> klass;
            try {
                klass = Class.forName(fqcn);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Classpath scanner found %s but reflection cannot load it".formatted(fqcn), e);
            }
            if (isTestClass(klass)) {
                // Test-fixture @AgentToolComponent beans (e.g. ToolNameDuplicationTest's
                // DupAlpha/DupBeta which intentionally probe a duplicate-name boot
                // failure) live on the test classpath and must not flag this rule.
                continue;
            }
            classes.add(klass);
        }
        return classes;
    }

    private static boolean isTestClass(Class<?> klass) {
        var source = klass.getProtectionDomain() == null ? null : klass.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return false;
        }
        String location = source.getLocation().toString();
        return location.contains("/test-classes/") || location.contains("\\test-classes\\");
    }
}
