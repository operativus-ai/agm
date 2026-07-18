package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.core.registry.ApprovalOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Domain Responsibility: Cross-cutting verification that {@link HitlAdvisor#requiresHitl(String)}
 * resolves the expected tier for every native tool method exposed by {@code @AgentToolComponent}
 * classes (the Agno-equivalent surface plus AGM-specific tools that share the same tier-resolution
 * code path).
 *
 * State: Stateless — {@code HitlAdvisor}'s SPI providers and dispatch counter are now instance
 * fields (per audit F8); each test gets a fresh advisor with empty SPI providers so the static
 * DESTRUCTIVE_TOOLS fallback semantics are pinned without JVM-state pollution. The FinOps gate is
 * built OFF here (production default), so {@code bulkIngestDocumentationSite} resolves to
 * {@code false} — the enabled path is covered by {@link HitlAdvisorTest}.
 *
 * <p>{@code run_python} is Tier 3 HITL-gated — added to {@code HitlAdvisor.DESTRUCTIVE_TOOLS}
 * in #1216 (live template-wired code execution), matching its {@code e2b_execute_python} sibling.
 */
class HitlAdvisorNativeToolWiringTest {

    private HitlAdvisor hitlAdvisor;

    @BeforeEach
    void buildAdvisor() {
        // Empty SPI providers list pins the static-set fallback semantics for native tool names.
        hitlAdvisor = new HitlAdvisor(mock(ApprovalOperations.class),
                mock(ai.operativus.agentmanager.control.repository.AgentRepository.class),
                mock(ai.operativus.agentmanager.control.service.HumanReviewService.class),
                new SimpleMeterRegistry(), List.of(), false, java.util.Set.of());
    }

    @ParameterizedTest(name = "[{index}] {0} → requiresHitl={1}")
    @CsvSource({
            // Native Tier 3 — destructive (in DESTRUCTIVE_TOOLS static set)
            "delete_database, true",
            "run_python, true",
            // Native Tier 1 — auto-execute (NOT in DESTRUCTIVE_TOOLS; FinOps gate OFF by default)
            // Agno-equivalent surface
            "webSearch, false",
            "stockPrice, false",
            "firecrawl_web_search, false",
            "firecrawl_scrape_url, false",
            "save_memory, false",
            "search_knowledge_base, false",
            // AGM-specific tools that share the tier-resolution code path
            "readWebpage, false",
            "pushToKnowledgeBase, false",
            // FinOps candidate — seeded in agm.hitl.finops.tools; gated ONLY when the gate is enabled
            "bulkIngestDocumentationSite, false",
            "searchKnowledgeBaseTool, false",
            "schedule_task, false",
            // Out-of-codebase tool name → falls through to false
            "nonexistent_tool_xyz, false"
    })
    void requiresHitl_nativeTools(String toolName, boolean expected) {
        assertEquals(expected, hitlAdvisor.requiresHitl(toolName),
                "tool=" + toolName + " requiresHitl tier mismatch vs HitlAdvisor DESTRUCTIVE_TOOLS/FINOPS_TOOLS");
    }
}
