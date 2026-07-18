import React, { useEffect, useState } from 'react';
import { Card } from '../../../shared/components/ui/Card';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { LuPlay, LuPause, LuTerminal, LuSettings } from 'react-icons/lu';
import { Link } from 'react-router-dom';
import { MonitoringApi } from '../api/monitoring-api';
import type { AgentStats } from '../api/monitoring-api';
import { incidentResponseApi } from '../../agents/api/incidentResponseApi';
import { logger } from '../../../utils/logger';

interface AgentDisplay {
  id: string;
  name: string;
  role: string;
  status: 'active' | 'idle' | 'error' | 'stopped';
  tasksCompleted: number;
  errorRuns: number;
  lastActive: string;
}

const StatusBadge: React.FC<{ status: AgentDisplay['status'] }> = ({ status }) => {
  const variants = {
    active: 'success',
    idle: 'warning',
    error: 'error',
    stopped: 'neutral'
  } as const;

  return (
    <Badge variant={variants[status]} size="sm" outline={false} className="uppercase font-bold text-[10px] tracking-wider">
      {status}
    </Badge>
  );
};

export const AgentGrid: React.FC = () => {
  const [agents, setAgents] = useState<AgentDisplay[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyAgentIds, setBusyAgentIds] = useState<Set<string>>(new Set());

  const togglePauseResume = async (agentId: string, currentlyActive: boolean) => {
    setBusyAgentIds(prev => new Set(prev).add(agentId));
    try {
      // Pause = quarantine (cancels active runs + locks credentials).
      // Resume = unquarantine (restores maintenanceMode=false + targeted credential re-enable).
      // Reason is auto-generated from the dashboard context — full incident-response
      // semantics with operator-supplied reasons are on the dedicated /admin/incident page.
      if (currentlyActive) {
        await incidentResponseApi.quarantine(agentId, 'Paused via dashboard quick-action');
      } else {
        await incidentResponseApi.unquarantine(agentId, 'Resumed via dashboard quick-action');
      }
      await fetchAgents();
    } catch (e) {
      logger.error('Pause/Resume failed:', e);
    } finally {
      setBusyAgentIds(prev => {
        const next = new Set(prev);
        next.delete(agentId);
        return next;
      });
    }
  };

  const fetchAgents = async () => {
      try {
        const data = await MonitoringApi.getStats();
        // Map backend stats to UI model
        const mappedAgents: AgentDisplay[] = data.agentStats.map((stat: AgentStats) => {
           let status: AgentDisplay['status'] = 'idle';
           if (stat.activeRuns > 0) status = 'active';

           let lastActive = 'Never';
           if (stat.lastRunAt) {
             try {
               // Simple time ago calculation if date-fns not installed, or use it if available.
               // Assuming standard ISO string.
               const date = new Date(stat.lastRunAt);
               const diff = Date.now() - date.getTime();
               const mins = Math.floor(diff / 60000);
               if (mins < 60) lastActive = `${mins}m ago`;
               else {
                 const hours = Math.floor(mins / 60);
                 if (hours < 24) lastActive = `${hours}h ago`;
                 else lastActive = `${Math.floor(hours / 24)}d ago`;
               }
             } catch (e) {
               lastActive = 'Unknown';
             }
           }

           return {
             id: stat.agentId,
             name: stat.agentName,
             role: 'AI Agent',
             status: status,
             tasksCompleted: stat.totalRuns,
             errorRuns: stat.errorRuns,
             lastActive: lastActive
           };
        });
        setAgents(mappedAgents);
      } catch (error) {
        logger.error('Failed to fetch agent stats', error);
      } finally {
        setLoading(false);
      }
    };

  useEffect(() => {
    fetchAgents();
    const interval = setInterval(fetchAgents, 30000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
     return <div className="flex justify-center p-12"><span className="loading loading-spinner loading-lg"></span></div>;
  }

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h2 className="text-xl font-bold">Active Agents</h2>
        <Button size="sm" variant="ghost" onClick={() => fetchAgents()}>Refresh</Button>
      </div>
      
      {agents.length === 0 ? (
        <div className="text-center p-12 text-theme-muted border border-dashed border-obsidian-stroke rounded-lg">
          No agents configured.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {agents.map((agent) => (
            <Card key={agent.id} className="hover:shadow-[0_0_20px_rgba(59,130,246,0.1)] transition-all duration-300 border-obsidian-stroke">
              <Card.Body className="p-6">
                <div className="flex justify-between items-start mb-4">
                  <div className="flex gap-3">
                    <div className="avatar placeholder">
                      <div className="rounded-lg w-12 h-12 flex items-center justify-center bg-obsidian-base border border-obsidian-stroke text-agent-blue">
                        <span className="text-xl font-bold font-mono">{(agent.name || 'A').charAt(0)}</span>
                      </div>
                    </div>
                    <div>
                      <h3 className="font-bold text-lg leading-tight text-theme-foreground">{agent.name || agent.id || 'Unknown Agent'}</h3>
                      <p className="text-xs text-theme-muted font-mono mt-1">{agent.role}</p>
                    </div>
                  </div>
                  <StatusBadge status={agent.status} />
                </div>

                <div className="grid grid-cols-3 gap-4 py-4 border-t border-b border-obsidian-stroke/30 my-2">
                   <div>
                      <div className="text-[10px] text-theme-muted uppercase tracking-widest font-bold">Tasks</div>
                      <div className="font-mono font-bold text-theme-foreground">{agent.tasksCompleted}</div>
                   </div>
                   <div>
                      <div className="text-[10px] text-theme-muted uppercase tracking-widest font-bold">Errors</div>
                      <div className={`font-mono font-bold ${agent.errorRuns > 0 ? 'text-error' : 'text-theme-foreground'}`}>
                        {agent.errorRuns}
                      </div>
                   </div>
                   <div>
                      <div className="text-[10px] text-theme-muted uppercase tracking-widest font-bold">Last Active</div>
                      <div className="font-mono font-bold text-theme-foreground">{agent.lastActive}</div>
                   </div>
                </div>

                <div className="flex justify-end gap-2 mt-2">
                   <Link to="/chat" state={{ agentId: agent.id }} title="Open Terminal">
                     <Button size="sm" variant="ghost" className="btn-square text-theme-muted hover:text-white hover:bg-obsidian-stroke/50">
                        <LuTerminal className="h-4 w-4" />
                     </Button>
                   </Link>
                   <Link to={`/agents/${agent.id}/edit`} title="Agent Settings">
                     <Button size="sm" variant="ghost" className="btn-square text-theme-muted hover:text-white hover:bg-obsidian-stroke/50">
                        <LuSettings className="h-4 w-4" />
                     </Button>
                   </Link>
                   {agent.status === 'active' ? (
                      <Button
                        size="sm"
                        variant="outline"
                        className="gap-2 text-warn-amber border-warn-amber hover:bg-warn-amber hover:text-black"
                        disabled={busyAgentIds.has(agent.id)}
                        onClick={() => togglePauseResume(agent.id, true)}
                      >
                         <LuPause className="h-3 w-3" /> {busyAgentIds.has(agent.id) ? 'Pausing…' : 'Pause'}
                      </Button>
                   ) : (
                      <Button
                        size="sm"
                        variant="outline"
                        className="gap-2 text-active-green border-active-green hover:bg-active-green hover:text-black"
                        disabled={busyAgentIds.has(agent.id)}
                        onClick={() => togglePauseResume(agent.id, false)}
                      >
                         <LuPlay className="h-3 w-3" /> {busyAgentIds.has(agent.id) ? 'Starting…' : 'Start'}
                      </Button>
                   )}
                </div>
              </Card.Body>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
};
