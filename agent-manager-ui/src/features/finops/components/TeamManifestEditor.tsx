import React, { useState } from 'react';
import { cn } from '../../../shared/utils/cn';

export interface AgentManifest {
    agentId: string;
    role: string;
    capabilities: string[];
    requiresPiiRedaction: boolean;
}

export interface TeamManifest {
    teamId: string;
    humanLead: string;
    maxDailySpend: number;
    minSpendingAuthority: number;
    allowedCapabilities: string[];
    agents: Record<string, AgentManifest>;
}

interface TeamManifestEditorProps {
    className?: string;
    manifest: TeamManifest;
    onSave?: (updatedManifest: TeamManifest) => void;
}

export const TeamManifestEditor: React.FC<TeamManifestEditorProps> = ({ className, manifest: initialManifest, onSave }) => {
    const [manifest, setManifest] = useState<TeamManifest>(initialManifest);

    const handleChange = (field: keyof TeamManifest, value: any) => {
        setManifest(prev => ({ ...prev, [field]: value }));
    };

    return (
        <div className={cn("bg-obsidian-elevated p-6 rounded-box border border-obsidian-stroke", className)}>
            <div className="flex justify-between items-center mb-6">
                <div>
                    <h2 className="text-xl font-bold flex items-center gap-2">
                        <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-6 h-6 text-primary">
                            <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m6.75 12l-3-3m0 0l-3 3m3-3v6m-1.5-15H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
                        </svg>
                        Team Manifest Editor
                    </h2>
                    <p className="text-sm text-base-content/70">YAML Property Ingestion & Enforcement</p>
                </div>
                <button
                    className="btn btn-primary"
                    onClick={() => onSave?.(manifest)}
                >
                    Apply YAML Policy
                </button>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                {/* Visual Form */}
                <div className="space-y-4">
                    <div className="form-control">
                        <label className="label"><span className="label-text">Team ID</span></label>
                        <input type="text" className="input input-bordered" value={manifest.teamId} onChange={e => handleChange('teamId', e.target.value)} />
                    </div>
                    
                    <div className="form-control">
                        <label className="label"><span className="label-text">Human Lead IAM</span></label>
                        <input type="text" className="input input-bordered" value={manifest.humanLead} onChange={e => handleChange('humanLead', e.target.value)} />
                    </div>

                    <div className="grid grid-cols-2 gap-4">
                        <div className="form-control">
                            <label className="label"><span className="label-text">Max Daily Spend ($)</span></label>
                            <input type="number" step="0.01" className="input input-bordered" value={manifest.maxDailySpend} onChange={e => handleChange('maxDailySpend', parseFloat(e.target.value))} />
                        </div>
                        <div className="form-control">
                            <label className="label"><span className="label-text">Min Auth ($)</span></label>
                            <input type="number" step="0.01" className="input input-bordered" value={manifest.minSpendingAuthority} onChange={e => handleChange('minSpendingAuthority', parseFloat(e.target.value))} />
                        </div>
                    </div>
                    
                    <div className="form-control">
                        <label className="label"><span className="label-text">Allowed Shared Capabilities</span></label>
                        <textarea className="textarea textarea-bordered font-mono h-24" value={manifest.allowedCapabilities.join('\n')} onChange={e => handleChange('allowedCapabilities', e.target.value.split('\n'))} />
                        <label className="label"><span className="label-text-alt text-base-content/60">One capability per line</span></label>
                    </div>
                </div>

                {/* Raw JSON/YAML Binding Preview */}
                <div>
                    <label className="label"><span className="label-text font-semibold.">Raw Interceptor Map (ReadOnly)</span></label>
                    <div className="mockup-code h-100 overflow-auto text-sm transition-all shadow-inner">
                        <pre data-prefix=">"><code className="text-warning"># Live gateway enforcement snapshot</code></pre>
                        <pre data-prefix=">"><code>{JSON.stringify(manifest, null, 2)}</code></pre>
                    </div>
                </div>
            </div>
        </div>
    );
};
