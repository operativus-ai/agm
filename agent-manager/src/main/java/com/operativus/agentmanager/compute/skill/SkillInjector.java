package com.operativus.agentmanager.compute.skill;

import com.operativus.agentmanager.core.entity.Skill;
import com.operativus.agentmanager.core.registry.SkillOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Domain Responsibility: Runtime composer that resolves an agent's active Skills and folds
 *     their {@code allowedTools} and {@code systemPromptSnippet} into the agent's direct
 *     tool list and instructions before the ChatClient is constructed. Crosses the
 *     {@code compute/} ⇄ {@code control/} boundary only via the {@link SkillOperations}
 *     SPI seam — never depends on {@code SkillRepository} or {@code SkillService} directly
 *     (enforced by {@code SkillInjectorBoundaryArchTest}).
 *
 *     <p>Soft-skip discipline: tool names that reference unregistered tools (not present in
 *     the caller-supplied {@code knownToolNames}) are logged at WARN and dropped. The agent
 *     still runs; missing tools just don't appear in the LLM's tool list. This avoids
 *     coupling skill-edits to a tool-registry redeploy.
 *
 *     <p>Deterministic ordering: skills arrive from {@link SkillOperations#findActiveSkillsForAgent}
 *     ordered by {@code priority ASC, created_at ASC}. The injection iterates in that order
 *     so tool dedup is stable across runs.
 *
 *     <p>Conditional registration: gated by {@code agm.skills.enabled=true}. When the flag
 *     is off, the bean is absent and {@code AgentClientFactory} must not call into the
 *     injector (Optional injection or {@code @Autowired(required = false)} on the
 *     factory side will be wired in PR-1e).
 * State: Stateless
 */
@Service
@ConditionalOnProperty(name = "agm.skills.enabled", havingValue = "true")
public class SkillInjector {

    private static final Logger log = LoggerFactory.getLogger(SkillInjector.class);

    private final SkillOperations skillOperations;

    public SkillInjector(SkillOperations skillOperations) {
        this.skillOperations = skillOperations;
    }

    /**
     * Resolves the active skills for {@code agentId} and returns the combined tool list and
     * system prompt to use for this run. The agent's direct configuration is the base; each
     * active skill (in repository order) augments it.
     *
     * @param agentId            agent whose attached skills are resolved
     * @param agentDirectTools   tool names already configured directly on the agent (may be empty)
     * @param agentInstructions  agent's stored {@code instructions} field (may be null/blank)
     * @param knownToolNames     superset of tool names registered in {@code ToolConfig.globalToolProvider}
     *                           — any skill tool not in this set is soft-skipped
     * @return combined tool list (deduped, ordered) and combined system prompt (with per-skill section headers)
     */
    public InjectionResult inject(String agentId,
                                  List<String> agentDirectTools,
                                  String agentInstructions,
                                  Set<String> knownToolNames) {
        List<String> directs = agentDirectTools != null ? agentDirectTools : Collections.emptyList();
        Set<String> known = knownToolNames != null ? knownToolNames : Collections.emptySet();

        List<Skill> skills = skillOperations.findActiveSkillsForAgent(agentId);
        if (skills.isEmpty()) {
            return new InjectionResult(new ArrayList<>(directs),
                    agentInstructions != null ? agentInstructions : "");
        }

        // LinkedHashSet preserves insertion order while auto-deduping. Agent direct
        // tools go first so they retain their original ordering, then skill tools
        // append in skill-priority order.
        Set<String> tools = new LinkedHashSet<>(directs);

        StringBuilder prompt = new StringBuilder(
                agentInstructions != null ? agentInstructions : "");

        for (Skill skill : skills) {
            for (String toolName : skill.getAllowedTools()) {
                if (!known.contains(toolName)) {
                    log.warn("Skill '{}' (id={}) references unknown tool '{}' — soft-skipping",
                            skill.getName(), skill.getId(), toolName);
                    continue;
                }
                tools.add(toolName);
            }
            String snippet = skill.getSystemPromptSnippet();
            if (snippet != null && !snippet.isBlank()) {
                if (prompt.length() > 0) prompt.append("\n\n");
                prompt.append("## Skill: ").append(skill.getName()).append("\n").append(snippet);
            }
        }

        return new InjectionResult(new ArrayList<>(tools), prompt.toString());
    }

    /**
     * Combined output of {@link #inject}. {@code tools} is order-preserving and deduped;
     * {@code systemPrompt} is the concatenation of the agent's instructions and each
     * skill's snippet (with {@code "## Skill: <name>"} headers between entries).
     */
    public record InjectionResult(List<String> tools, String systemPrompt) {
    }
}
