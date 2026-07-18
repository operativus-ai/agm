package com.operativus.agentmanager.core.model;

import java.util.List;

/**
 * Domain Responsibility: Request body for atomically adding multiple members to a team.
 */
public record BulkMemberRequest(
        List<MemberEntry> members
) {
    public record MemberEntry(String agentId, String role) {}
}
