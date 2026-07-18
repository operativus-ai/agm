import { useState } from 'react';
import { useSession } from './lib/session';
import { LoginView } from './LoginView';
import { AgentsPanel } from './panels/AgentsPanel';
import { KnowledgePanel } from './panels/KnowledgePanel';
import { WorkflowsPanel } from './panels/WorkflowsPanel';
import { TeamsPanel } from './panels/TeamsPanel';
import { ToolsPanel } from './panels/ToolsPanel';
import { GovernancePanel } from './panels/GovernancePanel';
import { SessionsPanel } from './panels/SessionsPanel';
import { MemoryPanel } from './panels/MemoryPanel';
import { ApprovalsPanel } from './panels/ApprovalsPanel';
import { SchedulesPanel } from './panels/SchedulesPanel';
import { JobsPanel } from './panels/JobsPanel';
import { A2APanel } from './panels/A2APanel';
import { ModelsPanel } from './panels/ModelsPanel';
import { SettingsPanel } from './panels/SettingsPanel';
import { MonitoringPanel } from './panels/MonitoringPanel';
import { EvaluationsPanel } from './panels/EvaluationsPanel';
import { ScenarioRunnerView } from './ScenarioRunnerView';
import type { JSX } from 'react';

type Tab = string;

const GROUPS: Array<{ group: string; items: Array<{ id: Tab; label: string; el: () => JSX.Element }> }> = [
  {
    group: 'Run',
    items: [
      { id: 'agents', label: 'Agents & Run', el: () => <AgentsPanel /> },
      { id: 'workflows', label: 'Workflows', el: () => <WorkflowsPanel /> },
      { id: 'teams', label: 'Teams', el: () => <TeamsPanel /> },
      { id: 'approvals', label: 'Approvals (HITL)', el: () => <ApprovalsPanel /> },
    ],
  },
  {
    group: 'Data',
    items: [
      { id: 'knowledge', label: 'Knowledge / RAG', el: () => <KnowledgePanel /> },
      { id: 'memory', label: 'Memory', el: () => <MemoryPanel /> },
      { id: 'sessions', label: 'Sessions', el: () => <SessionsPanel /> },
    ],
  },
  {
    group: 'Ops',
    items: [
      { id: 'schedules', label: 'Schedules', el: () => <SchedulesPanel /> },
      { id: 'jobs', label: 'Jobs', el: () => <JobsPanel /> },
      { id: 'a2a', label: 'A2A', el: () => <A2APanel /> },
      { id: 'tools', label: 'Tools', el: () => <ToolsPanel /> },
    ],
  },
  {
    group: 'Admin',
    items: [
      { id: 'models', label: 'Models', el: () => <ModelsPanel /> },
      { id: 'governance', label: 'Provider keys', el: () => <GovernancePanel /> },
      { id: 'settings', label: 'Settings', el: () => <SettingsPanel /> },
    ],
  },
  {
    group: 'Observe',
    items: [
      { id: 'monitoring', label: 'Monitoring', el: () => <MonitoringPanel /> },
      { id: 'evaluations', label: 'Evaluations', el: () => <EvaluationsPanel /> },
    ],
  },
  {
    group: 'Test',
    items: [{ id: 'scenarios', label: 'Scenario Runner', el: () => <ScenarioRunnerView /> }],
  },
];

const ALL = GROUPS.flatMap((g) => g.items);

export function App() {
  const { session, logout } = useSession();
  const [tab, setTab] = useState<Tab>('agents');

  if (!session) return <LoginView />;
  const active = ALL.find((i) => i.id === tab) ?? ALL[0];

  return (
    <div className="app shell">
      <aside className="sidebar">
        <div className="brand">AGM Console</div>
        {GROUPS.map((g) => (
          <div key={g.group} className="navgroup">
            <div className="navgroup-label">{g.group}</div>
            {g.items.map((i) => (
              <button key={i.id} className={`navitem ${tab === i.id ? 'active' : ''}`} onClick={() => setTab(i.id)}>
                {i.label}
              </button>
            ))}
          </div>
        ))}
        <div className="sidebar-foot">
          <span className="muted">{session.auth.username}</span>
          <button className="link" onClick={logout}>Sign out</button>
        </div>
      </aside>
      <main className="content">{active.el()}</main>
    </div>
  );
}
