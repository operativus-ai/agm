package com.operativus.agentmanager.integration.tools;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins the wire-level contract of {@code GET /api/tools}, the public
 *   surface that the frontend reads to populate the agent-tools picker. Asserts response
 *   shape, presence of the smoke-set of native tools (cross-checked with
 *   {@link ToolDiscoveryRuntimeTest}), and that {@code @RequiresCapability}-annotated tools
 *   resolve to the correct {@code ToolCategory} display label.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 *
 * <p>The path is {@code /api/tools}, NOT {@code /api/v1/tools} — the controller is one of
 * the legacy un-versioned endpoints (Requirement 3.5.1 in its javadoc). Pinning this means
 * a future relocation must update both the controller and this test in the same PR.</p>
 *
 * <p>This test exists because today the seam between {@code AgentClientFactory.getAvailableTools()}
 * (the {@code core.registry.ToolRegistry} interface) and the composite
 * {@code ToolConfig.globalToolProvider} is silently load-bearing — if T2 passes (discovery
 * sanity) and this test fails on a name, the bug is in the adapter inside
 * {@code AgentClientFactory}, not in discovery.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
class ToolControllerRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> TOOL_LIST_TYPE =
            new ParameterizedTypeReference<>() { };

    @Test
    void getTools_returnsAuthorizedListWithNativeSmokeSetAndCategoryResolution() {
        HttpHeaders auth = authenticateAs("tc-user", "tc-user@test.local", "pw-tc-12345",
                List.of("ROLE_USER"));

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url("/api/tools"), HttpMethod.GET, new HttpEntity<>(auth), TOOL_LIST_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = response.getBody();
        assertThat(items)
                .as("the controller must return a non-empty list under a real Spring context — empty means the AgentClientFactory→core ToolRegistry seam is broken")
                .isNotEmpty();

        Set<String> ids = items.stream()
                .map(m -> (String) m.get("id"))
                .collect(Collectors.toSet());

        assertThat(ids)
                .as("smoke set of native tools must reach the /api/tools surface — verifies the AgentClientFactory adapter agrees with globalToolProvider")
                .contains("delete_database", "schedule_task", "searchKnowledgeBaseTool",
                        "firecrawl_web_search", "firecrawl_scrape_url");

        items.forEach(item -> {
            assertThat(item.get("label"))
                    .as("every item must carry a non-blank human label (formatLabel result) — name='%s'", item.get("id"))
                    .isInstanceOf(String.class)
                    .satisfies(v -> assertThat((String) v).isNotBlank());
            assertThat(item.get("categoryLabel"))
                    .as("every item must carry a categoryLabel — unmapped tools default to 'General' — name='%s'", item.get("id"))
                    .isInstanceOf(String.class)
                    .satisfies(v -> assertThat((String) v).isNotBlank());
        });

        Map<String, Object> deleteDb = items.stream()
                .filter(m -> "delete_database".equals(m.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(deleteDb.get("category"))
                .as("delete_database carries @RequiresCapability(\"write_access=destructive\") → ToolCategory.PRIVILEGED")
                .isEqualTo("PRIVILEGED");
        assertThat(deleteDb.get("categoryLabel"))
                .as("ToolCategory.PRIVILEGED.getDisplayName() is 'Privileged Operations'")
                .isEqualTo("Privileged Operations");
    }

    @Test
    void getTools_withoutAuth_returns401() {
        ResponseEntity<String> response = rest.getForEntity(url("/api/tools"), String.class);
        assertThat(response.getStatusCode())
                .as("/api/tools is not in SecurityConfig publicPaths — anonymous requests must be rejected")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
