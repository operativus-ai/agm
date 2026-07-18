package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Aggregated health summary for a single team, used by the detail-page health endpoint.
 */
public record TeamHealthDTO(
        String teamId,
        int memberCount,
        int activeMemberCount,
        int inMaintenanceCount,
        LeaderInfo leaderAgent,
        int edgeCount,
        double currentDailySpend,
        double maxDailySpend
) {
    public record LeaderInfo(String id, String name, boolean active) {}
}
