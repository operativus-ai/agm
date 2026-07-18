package com.operativus.agentmanager.control.dto;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Domain Responsibility: Outbound wire shape for Skill resources.
 * State: Stateless (Data Transfer Object)
 */
public record SkillResponse(
        String id,
        String orgId,
        String name,
        String description,
        String systemPromptSnippet,
        Set<String> allowedTools,
        Boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
