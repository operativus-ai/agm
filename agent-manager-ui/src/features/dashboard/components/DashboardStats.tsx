import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Card } from '../../../shared/components/ui/Card';
import { LuBot, LuListTodo, LuServer, LuShieldAlert, LuTriangleAlert, LuDollarSign, LuZap, LuCalendarClock } from 'react-icons/lu';
import { MonitoringApi } from '../api/monitoring-api';
import type { GlobalStats } from '../api/monitoring-api';
import { logger } from '../../../utils/logger';
import { VelocitySparkline } from '../../../shared/components/charts/VelocitySparkline';
import { useActiveBurnRates, useHistoricalTrends, useRoiStats } from '../../finops/hooks/useFinOps';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import { RunStatus } from '../../../shared/types/enums';
import type { Schedule } from '../../../shared/types/orchestration';

export const DashboardStats: React.FC = () => {
  const [stats, setStats] = useState<GlobalStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [pendingHitlCount, setPendingHitlCount] = useState<number>(0);
  const [schedules, setSchedules] = useState<Schedule[]>([]);

  const { data: activeBurnRates = [] } = useActiveBurnRates();
  const { data: trendData = [] } = useHistoricalTrends(1);
  const { data: roiStats } = useRoiStats();

  // Aggregate burn rate across all active sessions (USD/hr)
  const aggregateBurnRate = activeBurnRates.length > 0
      ? activeBurnRates.reduce((sum, w) => sum + w.cumulativeUsd, 0) * 60
      : 0;

  useEffect(() => {
    const fetchStats = async () => {
      try {
        const data = await MonitoringApi.getStats();
        setStats(data);
      } catch (error) {
        logger.error('Failed to fetch dashboard stats', error);
      } finally {
        setLoading(false);
      }
    };

    fetchStats();
    const interval = setInterval(fetchStats, 30000);
    return () => clearInterval(interval);
  }, []);

  // Fetch pending HITL approval count
  useEffect(() => {
    const fetchPendingCount = async () => {
      try {
        const page = await orchestrationApi.getApprovals({ status: RunStatus.PENDING, page: 0, size: 1 });
        setPendingHitlCount(page.page.totalElements ?? 0);
      } catch {
        // Silently degrade — badge shows 0
      }
    };

    fetchPendingCount();
    const interval = setInterval(fetchPendingCount, 30000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    const fetchSchedules = async () => {
      try {
        const page = await orchestrationApi.getSchedules({ page: 0, size: 100 });
        setSchedules(page.content ?? []);
      } catch {
        // Silently degrade
      }
    };
    fetchSchedules();
    const interval = setInterval(fetchSchedules, 60000);
    return () => clearInterval(interval);
  }, []);

  const todaySpendUsd = trendData[trendData.length - 1]?.estimatedUsd ?? 0;
  const netRoiUsd = roiStats?.netRoiUsd ?? 0;
  const totalErrors = stats ? stats.agentStats.reduce((s, a) => s + a.errorRuns, 0) : null;
  const activeSchedules = schedules.filter(s => s.isActive).length;

  const statItems = [
    {
      title: 'Total Agents',
      value: stats ? stats.totalAgents.toString() : '-',
      desc: stats ? `${stats.agentStats.filter(a => a.activeRuns > 0).length} active, ${stats.agentStats.filter(a => a.activeRuns === 0).length} idle` : '',
      icon: LuBot,
      color: 'text-agent-blue'
    },
    {
      title: 'Active Runs',
      value: stats ? stats.totalActiveRuns.toString() : '-',
      desc: 'Currently processing',
      icon: LuListTodo,
      color: 'text-info-sky'
    },
    {
      title: 'Total Completed',
      value: stats ? stats.totalCompletedRuns.toString() : '-',
      desc: 'Lifetime executions',
      icon: LuServer,
      color: 'text-active-green'
    },
    {
      title: 'Pending HITL',
      value: pendingHitlCount.toString(),
      desc: pendingHitlCount > 0 ? 'Awaiting review' : 'No blockages',
      icon: LuShieldAlert,
      color: pendingHitlCount > 0 ? 'text-warn-amber' : 'text-theme-muted',
      href: '/approvals',
      alert: pendingHitlCount > 0,
    },
    {
      title: 'Total Errors',
      value: totalErrors !== null ? totalErrors.toString() : '-',
      desc: totalErrors === 0 ? 'No errors recorded' : 'Lifetime error runs',
      icon: LuTriangleAlert,
      color: totalErrors && totalErrors > 0 ? 'text-error' : 'text-theme-muted',
    },
    {
      title: "Today's Spend",
      value: `$${todaySpendUsd.toFixed(2)}`,
      desc: 'Estimated USD today',
      icon: LuDollarSign,
      color: 'text-warn-amber',
      href: '/finops',
    },
    {
      title: 'Cache ROI',
      value: netRoiUsd >= 0 ? `+$${netRoiUsd.toFixed(2)}` : `-$${Math.abs(netRoiUsd).toFixed(2)}`,
      desc: 'Net cache savings (session)',
      icon: LuZap,
      color: netRoiUsd >= 0 ? 'text-info-sky' : 'text-error',
      href: '/finops',
    },
    {
      title: 'Active Schedules',
      value: schedules.length > 0 ? activeSchedules.toString() : '-',
      desc: schedules.length > 0 ? `${schedules.length} total configured` : 'No schedules',
      icon: LuCalendarClock,
      color: 'text-active-green',
      href: '/schedules',
    },
  ];

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 xl:grid-cols-4 gap-6 mb-8">
      {statItems.map((stat) => {
        const cardContent = (
          <Card.Body className="flex flex-row items-center p-6 gap-4">
            <div className={`relative p-4 rounded-full bg-obsidian-base border border-obsidian-stroke/50 ${stat.color}`}>
              <stat.icon className="w-6 h-6" />
              {'alert' in stat && stat.alert && (
                <span className="absolute -top-1 -right-1 flex h-3.5 w-3.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-warn-amber opacity-75"></span>
                  <span className="relative inline-flex rounded-full h-3.5 w-3.5 bg-warn-amber"></span>
                </span>
              )}
            </div>
            <div className="flex-1 min-w-0">
               <div className="text-sm text-theme-muted font-medium uppercase tracking-wider">{stat.title}</div>
               <div className="flex items-center gap-2">
                 <div className="text-2xl font-bold font-mono text-theme-foreground">
                   {loading ? <span className="loading loading-spinner loading-xs"></span> : stat.value}
                 </div>
                 {stat.title === 'Active Runs' && (
                   <VelocitySparkline currentValue={aggregateBurnRate} maxPoints={12} isHighVelocity={aggregateBurnRate > 5.0} />
                 )}
               </div>
               <div className="text-xs text-theme-muted/80">{stat.desc}</div>
            </div>
          </Card.Body>
        );

        return (
          <Card key={stat.title}>
            {'href' in stat && stat.href ? (
              <Link to={stat.href} className="block">{cardContent}</Link>
            ) : (
              cardContent
            )}
          </Card>
        );
      })}
    </div>
  );
};
