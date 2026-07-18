import React from 'react';
import type { Team } from '../../../shared/types/orchestration';

interface TeamMemorySettingsProps {
  team: Pick<Team, 'memoryEnabled' | 'addHistoryToMessages' | 'isolateMemory'>;
  onChange: (updates: Partial<Team>) => void;
}

interface ToggleProps {
  label: string;
  description: string;
  checked: boolean;
  testId: string;
  onChange: (next: boolean) => void;
}

const Toggle: React.FC<ToggleProps> = ({ label, description, checked, testId, onChange }) => (
  <div className="form-control">
    <label className="mb-3 block">
      <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider block">{label}</span>
      <span className="text-xs text-(--theme-muted) font-normal mt-1 block">{description}</span>
    </label>
    <input
      type="checkbox"
      className="toggle toggle-primary"
      checked={checked}
      data-testid={testId}
      onChange={e => onChange(e.target.checked)}
    />
  </div>
);

export const TeamMemorySettings: React.FC<TeamMemorySettingsProps> = ({ team, onChange }) => (
  <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
    <Toggle
      label="Semantic Memory Graph"
      description="Extract insights to a global team-level knowledge graph."
      checked={team.memoryEnabled ?? false}
      testId="team-memory-enabled-toggle"
      onChange={next => onChange({ memoryEnabled: next })}
    />
    <Toggle
      label="Include Conversational History"
      description="Toggle multi-turn dialogue recall."
      checked={team.addHistoryToMessages ?? true}
      testId="team-add-history-toggle"
      onChange={next => onChange({ addHistoryToMessages: next })}
    />
    <Toggle
      label="Isolate Member Memory (§9 MEM-2)"
      description="When ON, each member keeps its own chat-memory bucket and does NOT see prior members' messages on this team's session. Default OFF preserves the cross-member transcript that Sequential / Coordinator handoffs typically rely on."
      checked={team.isolateMemory ?? false}
      testId="team-isolate-memory-toggle"
      onChange={next => onChange({ isolateMemory: next })}
    />
  </div>
);
