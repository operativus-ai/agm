package ai.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * Domain Responsibility: Inbound payload for Skill create/update operations.
 * State: Stateless (Data Transfer Object)
 */
public record SkillRequest(
        @NotBlank String name,
        String description,
        String systemPromptSnippet,
        Set<String> allowedTools,
        Boolean active
) {
}
