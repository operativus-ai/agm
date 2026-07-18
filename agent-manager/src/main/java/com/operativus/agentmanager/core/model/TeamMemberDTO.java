package com.operativus.agentmanager.core.model;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Acts as an immutable data transfer object representing an agent's membership and specific role within a multi-agent team.
 * State: Stateless (Immutable Record carrier)
 *
 * <p>{@code humanReview} (REQ-HR-1) is the unified HumanReview config attached
 * at the member level so operators can require approval when a specific member
 * is dispatched in a team flow. Null on members without HumanReview attached.
 */
public record TeamMemberDTO(
        String teamId,
        String agentId,
        String role,
        HumanReview humanReview,
        LocalDateTime joinedAt
) {}
