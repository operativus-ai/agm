import React, { useState } from 'react';
import type { RunOptions } from '../types';

interface ChatSettingsProps {
  options: RunOptions;
  onChange: (options: RunOptions) => void;
}

const SettingsIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/></svg>
);

export const ChatSettings: React.FC<ChatSettingsProps> = ({ options, onChange }) => {
  const [isOpen, setIsOpen] = useState(false);

  const handleChange = (field: keyof RunOptions, value: string | number | undefined) => {
    onChange({ ...options, [field]: value === '' ? undefined : value });
  };

  const hasOverrides = options.temperature !== undefined || options.model !== undefined || 
                       options.systemPrompt !== undefined || options.maxTokens !== undefined;

  return (
    <div className="relative">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className={`
          p-1.5 rounded-lg transition-all duration-200
          ${hasOverrides
            ? 'text-agent-blue bg-agent-blue/10 hover:bg-agent-blue/20 ring-1 ring-agent-blue/30'
            : 'text-theme-muted hover:text-theme-foreground hover:bg-obsidian-elevated'
          }
        `}
        title="Runtime Configuration Overrides"
      >
        <SettingsIcon />
      </button>

      {isOpen && (
        <>
          {/* Backdrop */}
          <div 
            className="fixed inset-0 z-40" 
            onClick={() => setIsOpen(false)} 
          />
          
          {/* Panel */}
          <div className="absolute bottom-full right-0 mb-2 z-50 w-80
                          bg-obsidian-elevated rounded-xl shadow-2xl
                          border border-obsidian-stroke/50
                          backdrop-blur-xl p-4 space-y-4
                          animate-in slide-in-from-bottom-2 fade-in duration-200">

            <div className="flex items-center justify-between">
              <h4 className="text-sm font-semibold text-theme-foreground">Runtime Overrides</h4>
              {hasOverrides && (
                <button 
                  onClick={() => onChange({})}
                  className="text-[10px] text-error hover:text-error/80 transition-colors"
                >
                  Reset All
                </button>
              )}
            </div>

            {/* Temperature */}
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-theme-muted flex justify-between">
                <span>Temperature</span>
                <span className="text-agent-blue font-mono">
                  {options.temperature !== undefined ? options.temperature.toFixed(2) : 'Default'}
                </span>
              </label>
              <input 
                type="range" 
                min="0" 
                max="2" 
                step="0.05" 
                value={options.temperature ?? 0.7}
                onChange={(e) => handleChange('temperature', parseFloat(e.target.value))}
                className="range range-primary range-xs w-full"
              />
              <div className="flex justify-between text-[9px] text-theme-muted">
                <span>Precise</span>
                <span>Creative</span>
              </div>
            </div>

            {/* Model Override */}
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-theme-muted">Model Override</label>
              <input
                type="text"
                placeholder="e.g. gpt-4o, claude-3-opus"
                value={options.model ?? ''}
                onChange={(e) => handleChange('model', e.target.value)}
                className="input input-bordered input-xs w-full bg-obsidian-elevated border-obsidian-stroke text-sm"
              />
            </div>

            {/* Max Tokens */}
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-theme-muted">Max Tokens</label>
              <input
                type="number"
                placeholder="Default"
                min={1}
                max={128000}
                value={options.maxTokens ?? ''}
                onChange={(e) => handleChange('maxTokens', e.target.value ? parseInt(e.target.value) : undefined)}
                className="input input-bordered input-xs w-full bg-obsidian-elevated border-obsidian-stroke text-sm"
              />
            </div>

            {/* System Prompt Override */}
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-theme-muted">System Prompt Override</label>
              <textarea
                placeholder="Override the agent's system prompt..."
                value={options.systemPrompt ?? ''}
                onChange={(e) => handleChange('systemPrompt', e.target.value)}
                rows={3}
                className="textarea textarea-bordered textarea-xs w-full bg-obsidian-elevated border-obsidian-stroke text-sm resize-none"
              />
            </div>

            <p className="text-[9px] text-theme-muted/50 text-center">
              Overrides apply to this session only. Empty fields use agent defaults.
            </p>
          </div>
        </>
      )}
    </div>
  );
};
