import React from 'react';
import type { TeamHealth } from '../../../shared/types/orchestration';
import { Badge } from '../../../shared/components/ui/Badge';

/**
 * Health summary strip for an existing team (member counts, leader, daily spend).
 * Extracted from TeamDetailsPage — pure presentational, owns no state. The page
 * still gates it on `!isNew && health`.
 */
export const TeamHealthBar: React.FC<{ health: TeamHealth }> = ({ health }) => (
    <section className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
        <div className="flex flex-wrap items-center gap-4 text-xs">
            {(() => {
                const total = health.memberCount;
                const active = health.activeMemberCount;
                const fullyHealthy = total > 0 && active === total;
                const variant: 'success' | 'warning' | 'error' = total === 0
                    ? 'error'
                    : fullyHealthy ? 'success' : 'warning';
                const label = total === 0
                    ? 'No members'
                    : fullyHealthy ? 'Healthy' : 'Degraded';
                return (
                    <Badge variant={variant} outline className="text-xs font-mono">{label}</Badge>
                );
            })()}
            <span className="text-(--theme-muted)">
                <span className="font-mono">{health.activeMemberCount}</span>
                {' / '}
                <span className="font-mono">{health.memberCount}</span>
                {' members active'}
                {health.inMaintenanceCount > 0 && (
                    <>
                        {' · '}
                        <span className="font-mono text-warning">{health.inMaintenanceCount}</span>
                        {' in maintenance'}
                    </>
                )}
            </span>
            <span className="text-(--theme-muted)">
                <span className="font-mono">{health.edgeCount}</span>
                {' transition edges'}
            </span>
            {health.leaderAgent && (
                <span className="text-(--theme-muted)">
                    Leader:{' '}
                    <span className={`font-mono ${health.leaderAgent.active ? 'text-primary' : 'text-error'}`}>
                        {health.leaderAgent.name}
                    </span>
                    {!health.leaderAgent.active && (
                        <Badge variant="error" outline className="text-[10px] ml-1">inactive</Badge>
                    )}
                </span>
            )}
            {health.maxDailySpend > 0 && (
                <span className="text-(--theme-muted) ml-auto">
                    <span className="font-mono">${health.currentDailySpend.toFixed(2)}</span>
                    {' / '}
                    <span className="font-mono">${health.maxDailySpend.toFixed(2)}</span>
                    {' daily spend'}
                </span>
            )}
        </div>
    </section>
);
