package ai.operativus.agentmanager.compute.skill;

import ai.operativus.agentmanager.core.entity.Skill;
import ai.operativus.agentmanager.core.registry.SkillOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SkillInjectorTest {

    @Mock private SkillOperations skillOperations;

    private SkillInjector newInjector() {
        return new SkillInjector(skillOperations);
    }

    private Skill skill(String id, String name, Set<String> tools, String snippet) {
        Skill s = new Skill(id, "ORG", name, "desc", snippet);
        s.setAllowedTools(new HashSet<>(tools));
        return s;
    }

    // --- empty / null inputs ---

    @Test
    void inject_noSkillsAttached_returnsAgentDirectsUnchanged() {
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of());

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1",
                List.of("agent_tool_a", "agent_tool_b"),
                "You are a helpful agent.",
                Set.of("agent_tool_a", "agent_tool_b"));

        assertEquals(List.of("agent_tool_a", "agent_tool_b"), result.tools());
        assertEquals("You are a helpful agent.", result.systemPrompt());
    }

    @Test
    void inject_nullAgentInstructions_returnsEmptyStringWhenNoSkills() {
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of());

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of(), null, Set.of());

        assertEquals("", result.systemPrompt());
        assertTrue(result.tools().isEmpty());
    }

    @Test
    void inject_nullAgentDirectTools_handledAsEmpty() {
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of());

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", null, "instructions", null);

        assertEquals(List.of(), result.tools());
        assertEquals("instructions", result.systemPrompt());
    }

    // --- happy path ---

    @Test
    void inject_singleSkill_appendsToolsAndRendersSnippetWithHeader() {
        Skill weather = skill("s1", "weather",
                Set.of("weather_lookup"), "Use the weather_lookup tool for weather questions.");
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(weather));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1",
                List.of("base_tool"),
                "You are helpful.",
                Set.of("base_tool", "weather_lookup"));

        assertEquals(List.of("base_tool", "weather_lookup"), result.tools());
        assertTrue(result.systemPrompt().startsWith("You are helpful."));
        assertTrue(result.systemPrompt().contains("## Skill: weather"));
        assertTrue(result.systemPrompt().contains("Use the weather_lookup tool"));
    }

    @Test
    void inject_blankSnippet_omitsHeader() {
        Skill toolsOnly = skill("s1", "tools-only", Set.of("tool_a"), "");
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(toolsOnly));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of(), "Base.", Set.of("tool_a"));

        assertEquals("Base.", result.systemPrompt(),
                "Skill with no snippet must not contribute a header");
        assertEquals(List.of("tool_a"), result.tools());
    }

    @Test
    void inject_nullSnippet_omitsHeader() {
        Skill nullSnippet = skill("s1", "null-snippet", Set.of("tool_a"), null);
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(nullSnippet));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of(), "Base.", Set.of("tool_a"));

        assertEquals("Base.", result.systemPrompt());
    }

    // --- dedup ---

    @Test
    void inject_toolAlreadyOnAgent_doesNotDuplicate() {
        Skill skill = skill("s1", "weather",
                Set.of("weather_lookup"), "snippet");
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(skill));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1",
                List.of("weather_lookup", "calc"),
                "Base.",
                Set.of("weather_lookup", "calc"));

        assertEquals(2, result.tools().size(), "agent's direct tool must not be added twice");
        assertEquals(List.of("weather_lookup", "calc"), result.tools(),
                "agent direct tool order preserved when skill re-adds the same tool");
    }

    @Test
    void inject_twoSkillsReferenceSameTool_dedupAcrossSkills() {
        Skill s1 = skill("s1", "alpha", Set.of("shared_tool"), "alpha snippet");
        Skill s2 = skill("s2", "beta", Set.of("shared_tool"), "beta snippet");
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(s1, s2));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of(), "Base.", Set.of("shared_tool"));

        assertEquals(List.of("shared_tool"), result.tools(),
                "Same tool referenced by two skills must be deduped");
        assertTrue(result.systemPrompt().contains("## Skill: alpha"));
        assertTrue(result.systemPrompt().contains("## Skill: beta"),
                "Snippets are NOT deduped — both contribute their section header");
    }

    // --- soft-skip ---

    @Test
    void inject_unknownTool_softSkipsAndLogsWarning() {
        Skill skill = skill("s1", "weather",
                new LinkedHashSet<>(List.of("known_tool", "unknown_tool")),
                "snippet");
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(skill));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of(), "Base.", Set.of("known_tool"));

        assertEquals(List.of("known_tool"), result.tools(),
                "Unknown tool must be filtered out");
        assertTrue(result.systemPrompt().contains("## Skill: weather"),
                "Soft-skipping a missing tool must not block the snippet from rendering");
    }

    @Test
    void inject_allToolsUnknown_skillContributesOnlySnippet() {
        Skill skill = skill("s1", "weather", Set.of("ghost_a", "ghost_b"), "Only snippet remains.");
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(skill));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of("base"), "Base.", Set.of("base"));

        assertEquals(List.of("base"), result.tools());
        assertTrue(result.systemPrompt().contains("Only snippet remains."));
    }

    @Test
    void inject_nullKnownToolNames_treatsAllAsUnknown() {
        Skill skill = skill("s1", "weather", Set.of("weather_lookup"), "snippet");
        when(skillOperations.findActiveSkillsForAgent("agent-1")).thenReturn(List.of(skill));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of(), "Base.", null);

        assertTrue(result.tools().isEmpty(),
                "Null known-tool set means no tools can be verified — all soft-skip");
    }

    // --- ordering ---

    @Test
    void inject_multipleSkills_appliesInRepoOrder() {
        Skill first = skill("s-first", "first", Set.of("tool_first"), "First snippet.");
        Skill second = skill("s-second", "second", Set.of("tool_second"), "Second snippet.");
        // Repo returns them in priority/created_at order — injector must preserve that
        when(skillOperations.findActiveSkillsForAgent("agent-1"))
                .thenReturn(List.of(first, second));

        SkillInjector.InjectionResult result = newInjector().inject(
                "agent-1", List.of(), "Base.", Set.of("tool_first", "tool_second"));

        int firstIdx = result.systemPrompt().indexOf("## Skill: first");
        int secondIdx = result.systemPrompt().indexOf("## Skill: second");
        assertTrue(firstIdx > 0 && secondIdx > firstIdx,
                "Skill snippets must appear in the order SkillOperations returns them");
        assertEquals(List.of("tool_first", "tool_second"), result.tools());
    }
}
