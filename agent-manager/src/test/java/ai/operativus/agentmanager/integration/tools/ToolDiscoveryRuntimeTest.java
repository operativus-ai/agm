package ai.operativus.agentmanager.integration.tools;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins cross-cutting invariants on every tool callback discovered by
 *   {@code ToolConfig.globalToolProvider} when the full Spring context boots — every callback
 *   has a non-empty description, every name matches the LLM-safe identifier regex, no two
 *   callbacks share a name, and the smoke set of well-known native tools is present.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. The test does not invoke any tool — it only inspects the
 *   discovered metadata, so live Composio / model credentials are not required.
 *
 * <p>Today a bean annotated {@code @Component} instead of {@code @AgentToolComponent}
 * disappears from the LLM surface silently. This test surfaces those regressions and any
 * future schema drift (empty description, illegal name character, duplicate name) the next
 * time {@code ./mvnw test -Dgroups=integration} runs.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
class ToolDiscoveryRuntimeTest extends BaseIntegrationTest {

    private static final Pattern LLM_SAFE_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    @Autowired
    private ToolCallbackProvider globalToolProvider;

    private List<ToolCallback> callbacks;

    @BeforeEach
    void snapshotCallbacks() {
        callbacks = Arrays.asList(globalToolProvider.getToolCallbacks());
        assertThat(callbacks)
                .as("globalToolProvider must register at least one tool — empty discovery means the @AgentToolComponent scan misfired")
                .isNotEmpty();
    }

    @Test
    void everyToolHasNonEmptyDescription() {
        List<String> missing = callbacks.stream()
                .filter(cb -> cb.getToolDefinition().description() == null
                        || cb.getToolDefinition().description().isBlank())
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toList());
        assertThat(missing)
                .as("the LLM uses tool descriptions for selection — a blank description is a silent regression")
                .isEmpty();
    }

    @Test
    void everyToolNameMatchesLlmSafeRegex() {
        List<String> illegal = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .filter(name -> name == null || !LLM_SAFE_NAME.matcher(name).matches())
                .collect(Collectors.toList());
        assertThat(illegal)
                .as("OpenAI / Anthropic tool-name validation rejects names that do not match ^[a-zA-Z][a-zA-Z0-9_]*$ — Spring AI surfaces this only at call time")
                .isEmpty();
    }

    @Test
    void noDuplicateToolNames() {
        Map<String, Long> counts = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.groupingBy(n -> n, Collectors.counting()));
        List<String> dups = counts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        assertThat(dups)
                .as("two callbacks sharing a name means the LLM cannot disambiguate — last-write-wins behavior is undefined")
                .isEmpty();
    }

    @Test
    void expectedNativeToolsPresent() {
        Set<String> names = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());

        // Smoke set: a stable subset of native @AgentToolComponent tools whose absence
        // would mean a bean annotation was dropped or component scan was scoped down.
        // Each name is asserted independently so the failure message lists the missing one.
        assertThat(names)
                .as("delete_database is a Tier-3 destructive native tool (SensitiveOperationsTool) — its absence breaks HitlAdvisor's DESTRUCTIVE_TOOLS fallback path")
                .contains("delete_database");
        assertThat(names)
                .as("schedule_task is exposed by AgentSchedulingTool")
                .contains("schedule_task");
        assertThat(names)
                .as("searchKnowledgeBaseTool is exposed by ResearchTools (wraps KnowledgeTools) — its absence breaks RAG-driven retrieval")
                .contains("searchKnowledgeBaseTool");
        assertThat(names)
                .as("firecrawl_web_search is exposed by FirecrawlSearchTool — explicit @Tool(name=...)")
                .contains("firecrawl_web_search");
        assertThat(names)
                .as("firecrawl_scrape_url is exposed by FirecrawlSearchTool — explicit @Tool(name=...)")
                .contains("firecrawl_scrape_url");
    }
}
