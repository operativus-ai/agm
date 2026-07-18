import React from 'react';
import { useExtensions } from '../../extensions/api/extensionApi';

interface AgentHookSelectorProps {
  selectedHookIds: string[];
  onChange: (hookIds: string[]) => void;
  type: 'PRE' | 'POST';
}

export const AgentHookSelector: React.FC<AgentHookSelectorProps> = ({ selectedHookIds, onChange, type }) => {
  const { data: extensions, isLoading } = useExtensions();

  // Both Webhooks and MCPs can serve as hooks, or perhaps future native SPI hooks surfaced through the same API
  const hooks = extensions?.filter(ext => ext.active) || [];

  const handleToggle = (hookId: string) => {
    if (selectedHookIds.includes(hookId)) {
      onChange(selectedHookIds.filter(id => id !== hookId));
    } else {
      onChange([...selectedHookIds, hookId]);
    }
  };

  if (isLoading) {
    return <div className="animate-pulse h-12 bg-obsidian-surface rounded-lg"></div>;
  }

  return (
    <div className="space-y-3">
      <h3 className="text-sm font-bold text-theme-muted tracking-wider uppercase">
        {type === 'PRE' ? 'Pre-Execution Hooks (Interceptors)' : 'Post-Execution Hooks (Transformers)'}
      </h3>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {hooks.map(hook => {
          const isSelected = selectedHookIds.includes(hook.id!);
          return (
            <div
              key={hook.id}
              className={`p-3 rounded-lg border cursor-pointer transition-colors flex flex-col gap-2 ${
                isSelected ? 'border-agent-blue/50 bg-agent-blue/5' : 'border-obsidian-stroke bg-obsidian-surface hover:border-agent-blue/50'
              }`}
              onClick={() => handleToggle(hook.id!)}
            >
              <div className="flex flex-col">
                <span className="text-sm font-semibold text-theme-foreground">{hook.name}</span>
                <span className="text-xs text-theme-muted">{hook.type}</span>
              </div>
              <input
                type="checkbox"
                className="checkbox checkbox-primary checkbox-sm"
                checked={isSelected}
                readOnly
              />
            </div>
          );
        })}
        {hooks.length === 0 && (
          <div className="col-span-full text-xs text-theme-muted italic p-2 border border-dashed border-obsidian-stroke rounded-lg">
            No active extensions available. Register an extension to use as a hook.
          </div>
        )}
      </div>
    </div>
  );
};
