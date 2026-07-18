package com.operativus.agentmanager.arch;

import com.operativus.agentmanager.compute.tools.AgentToolComponent;
import com.operativus.agentmanager.core.model.AgentTemplate;
import com.operativus.agentmanager.core.model.SystemTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Build-time forward guard that every {@code defaultTools} entry on a
 *   {@link AgentTemplate#builtInTemplates() built-in template} resolves to a tool that actually
 *   exists. {@code AgentClientFactory.resolveTools} attaches a tool only when its name (or the
 *   snake→camel normalization of it) is present in the registry; an unknown name is silently
 *   dropped with a WARN, so an agent created from a template with a phantom tool boots fine but is
 *   missing capabilities at runtime — invisible until a user request fails. This happened with the
 *   "Web Scraper & Ingestion" template, which listed {@code "web_crawl"} (no such {@code @Tool}); the
 *   agent could read a page but could neither bulk-ingest a site nor push to the KB.
 *
 *   <p>Known names = the effective {@code @Tool} name of every method on every
 *   {@link AgentToolComponent} bean (mirrors {@link AgentToolDuplicateNameArchTest}) ∪ every
 *   {@link SystemTool} name. Composio/MCP tools are runtime/dynamic and not statically known — no
 *   built-in template references them today; if one ever does, add a tagged allowlist here.
 *
 * State: Stateless. Pure-classpath reflection — no Spring context, no DB. Runs in {@code ./mvnw test}.
 */
public class AgentTemplateToolsResolveArchTest {

    private static final String SCAN_BASE_PACKAGE = "com.operativus.agentmanager";

    @Test
    void everyBuiltInTemplateToolResolvesToARegisteredTool() {
        Set<String> known = knownToolNames();

        Map<String, List<String>> phantomsByTemplate = new LinkedHashMap<>();
        for (AgentTemplate t : AgentTemplate.builtInTemplates()) {
            if (t.defaultTools() == null) continue;
            List<String> phantoms = t.defaultTools().stream()
                    .filter(name -> !resolves(name, known))
                    .toList();
            if (!phantoms.isEmpty()) {
                phantomsByTemplate.put(t.id(), phantoms);
            }
        }

        if (!phantomsByTemplate.isEmpty()) {
            String report = phantomsByTemplate.entrySet().stream()
                    .map(e -> "  - template '%s' references unknown tool(s): %s".formatted(e.getKey(), e.getValue()))
                    .collect(Collectors.joining("\n"));
            fail(("""
                    A built-in AgentTemplate references tool name(s) that no @Tool / SystemTool declares.
                    resolveTools would silently drop them, so an agent created from the template would
                    boot missing those capabilities. Fix the template's defaultTools to use real tool
                    names (the effective @Tool name, or its snake_case form).

                    %d template(s):
                    %s

                    Known tool names:
                    %s""").formatted(phantomsByTemplate.size(), report, String.join(", ", new TreeSet<>(known))));
        }
    }

    /** Mirrors AgentClientFactory.resolveTools: exact match, else snake→camel normalized match. */
    private static boolean resolves(String name, Set<String> known) {
        return known.contains(name) || known.contains(toCamelCase(name));
    }

    private static Set<String> knownToolNames() {
        Set<String> names = new TreeSet<>();
        for (SystemTool st : SystemTool.values()) {
            names.add(st.getToolName());
        }
        for (Class<?> toolBean : findAgentToolComponentClasses()) {
            for (Method method : toolBean.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) continue;
                names.add(annotation.name().isEmpty() ? method.getName() : annotation.name());
            }
        }
        return names;
    }

    private static List<Class<?>> findAgentToolComponentClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentToolComponent.class));

        List<Class<?>> classes = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(SCAN_BASE_PACKAGE)) {
            String fqcn = bd.getBeanClassName();
            if (fqcn == null) continue;
            try {
                classes.add(Class.forName(fqcn));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Classpath scanner found %s but reflection cannot load it".formatted(fqcn), e);
            }
        }
        return classes;
    }

    private static String toCamelCase(String snake) {
        if (snake == null || !snake.contains("_")) return snake;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
