import React, { useState } from 'react';
import { CronExpressionParser } from 'cron-parser';
import cronstrue from 'cronstrue';
import type { Schedule } from '../../../shared/types/orchestration';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import { logger } from '../../../utils/logger';
import { Typography } from '../../../shared/components/ui/Typography';
import { Input } from '../../../shared/components/ui/Input';
import { Select } from '../../../shared/components/ui/Select';
import { Textarea } from '../../../shared/components/ui/Textarea';
import { Alert } from '../../../shared/components/ui/Alert';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';

interface ScheduleFormModalProps {
    onClose: () => void;
    schedule?: Schedule;
}

export const ScheduleFormModal: React.FC<ScheduleFormModalProps> = ({ onClose, schedule }) => {
    const [formData, setFormData] = useState<Partial<Schedule>>(schedule || {
        name: '',
        description: '',
        targetType: 'AGENT',
        targetId: '',
        cronExpression: '',
        contextualPrompt: '',
        resumeSessionId: '',
        isActive: true
    });
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [cronPreset, setCronPreset] = useState<string>('custom');
    const [cronError, setCronError] = useState<string | null>(null);
    const [cronPreview, setCronPreview] = useState<string[]>([]);
    const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose);

    const updateField = <K extends keyof Schedule>(key: K, value: Schedule[K]) => {
        setFormData(prev => ({ ...prev, [key]: value }));
        markDirty();
    };

    const validateCron = (expr: string) => {
        if (!expr.trim()) {
            setCronError(null);
            setCronPreview([]);
            return;
        }
        try {
            const parsed = CronExpressionParser.parse(expr);
            const next: string[] = [];
            for (let i = 0; i < 3; i++) {
                next.push(parsed.next().toDate().toLocaleString());
            }
            setCronPreview(next);
            setCronError(null);
        } catch {
            setCronError('Invalid Spring cron expression. Expected: second minute hour day-of-month month day-of-week');
            setCronPreview([]);
        }
    };

    const [activeTab, setActiveTab] = useState<'config' | 'target' | 'context'>('config');

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            setSubmitting(true);
            setError(null);
            
            if (schedule?.id) {
                await orchestrationApi.updateSchedule(schedule.id, formData);
            } else {
                await orchestrationApi.createSchedule(formData);
            }
            resetDirty();
            onClose();
        } catch (err: any) {
             logger.error("Failed to save schedule", err);
             setError(err.message || 'Failed to save schedule.');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <dialog className="modal modal-open">
            <div className="modal-box w-11/12 max-w-3xl bg-obsidian-base border border-obsidian-stroke shadow-2xl p-0 overflow-hidden flex flex-col h-[75vh]">
                <button
                    type="button"
                    className="btn btn-sm btn-circle btn-ghost absolute right-4 top-4 text-theme-muted hover:text-white z-10"
                    onClick={guardedClose}
                >
                  ✕
                </button>
                <div className="px-6 pt-6 pb-0 border-b border-obsidian-stroke">
                    <Typography.Heading level={3} className="text-theme-foreground">{schedule ? 'Edit Schedule' : 'Create Schedule'}</Typography.Heading>
                    <Typography.Text variant="muted">Define automated execution intervals.</Typography.Text>
                    
                    <div className="tabs tabs-bordered mt-6 overflow-x-auto flex-nowrap custom-scrollbar w-full">
                        <button 
                            type="button"
                            className={`tab font-bold tracking-wider uppercase text-[11px] whitespace-nowrap min-w-max pb-3 ${activeTab === 'config' ? 'tab-active text-agent-blue border-agent-blue' : 'text-theme-muted hover:text-white'}`}
                            onClick={() => setActiveTab('config')}
                        >Configuration</button>
                        <button 
                            type="button"
                            className={`tab font-bold tracking-wider uppercase text-[11px] whitespace-nowrap min-w-max pb-3 ${activeTab === 'target' ? 'tab-active text-agent-blue border-agent-blue' : 'text-theme-muted hover:text-white'}`}
                            onClick={() => setActiveTab('target')}
                        >Target Execution</button>
                        <button 
                            type="button"
                            className={`tab font-bold tracking-wider uppercase text-[11px] whitespace-nowrap min-w-max pb-3 ${activeTab === 'context' ? 'tab-active text-agent-blue border-agent-blue' : 'text-theme-muted hover:text-white'}`}
                            onClick={() => setActiveTab('context')}
                        >Context Recovery</button>
                    </div>
                </div>

                <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto p-6 space-y-6">
                    {error && (
                        <Alert severity="error">{error}</Alert>
                    )}

                    {activeTab === 'config' && (
                        <div className="space-y-6 animate-in fade-in duration-300">
                            <Input
                                label="Name"
                                value={formData.name || ''}
                                onChange={e => updateField('name', e.target.value)}
                                required
                            />

                            <Textarea
                                label="Description"
                                value={formData.description || ''}
                                onChange={e => updateField('description', e.target.value)}
                                minRows={3}
                                maxRows={5}
                                className="resize-none"
                            />

                            <div className="form-control border border-obsidian-stroke rounded-xl bg-obsidian-base overflow-hidden hover:border-agent-blue/50 transition-colors p-5 mt-8 w-full shadow-sm">
                                <label className="mb-4 block">
                                    <span className="text-xs font-bold text-theme-muted uppercase tracking-wider block">Active Status</span>
                                    <span className="text-xs text-theme-muted font-normal mt-1 block">Schedule will trigger immediately if active and past due.</span>
                                </label>
                                <div className="flex items-center">
                                    <input
                                        type="checkbox"
                                        className="toggle toggle-primary border-obsidian-stroke bg-obsidian-surface"
                                        checked={formData.isActive}
                                        onChange={e => updateField('isActive', e.target.checked)}
                                    />
                                </div>
                            </div>
                        </div>
                    )}

                    {activeTab === 'target' && (
                        <div className="space-y-6 animate-in fade-in duration-300">
                            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                                <Select
                                    label="Target Type"
                                    value={formData.targetType}
                                    onChange={e => updateField('targetType', e.target.value as Schedule['targetType'])}
                                    options={[
                                        { label: 'Agent', value: 'AGENT' },
                                        { label: 'Team', value: 'TEAM' },
                                        { label: 'Workflow', value: 'WORKFLOW' },
                                    ]}
                                />
                                <Input
                                    label="Target ID"
                                    value={formData.targetId}
                                    onChange={e => updateField('targetId', e.target.value)}
                                    placeholder={`Enter ${formData.targetType?.toLowerCase()} ID`}
                                    required
                                    className="font-mono text-sm"
                                />
                            </div>

                            <div className="space-y-2">
                                <Select
                                    label="Quick Preset"
                                    value={cronPreset}
                                    onChange={e => {
                                        const v = e.target.value;
                                        setCronPreset(v);
                                        if (v !== 'custom') {
                                            updateField('cronExpression', v);
                                            validateCron(v);
                                        }
                                    }}
                                    options={[
                                        { label: 'Hourly (every hour)', value: '0 0 * * * *' },
                                        { label: 'Daily at 8:00 AM', value: '0 0 8 * * *' },
                                        { label: 'Weekly (Mon 9:00 AM)', value: '0 0 9 * * 1' },
                                        { label: 'Custom', value: 'custom' },
                                    ]}
                                />

                                <Input
                                    label="Cron Expression (Spring Format)"
                                    value={formData.cronExpression}
                                    onChange={e => {
                                        updateField('cronExpression', e.target.value);
                                        setCronPreset('custom');
                                        setCronError(null);
                                        setCronPreview([]);
                                    }}
                                    onBlur={e => validateCron(e.target.value)}
                                    required
                                    className="font-mono"
                                    description="e.g., 0 0 8 * * * (Daily at 8:00 AM)"
                                    helpText="Spring 6-field: second minute hour day-of-month month day-of-week"
                                />

                                {cronError && (
                                    <p className="text-xs text-error mt-1">{cronError}</p>
                                )}
                                {cronPreview.length > 0 && !cronError && (
                                    <div className="text-xs bg-obsidian-surface rounded p-2 space-y-0.5 border border-obsidian-stroke">
                                        <p className="font-semibold text-theme-foreground">
                                            {(() => { try { return cronstrue.toString(formData.cronExpression || ''); } catch { return ''; } })()}
                                        </p>
                                        <p className="text-theme-muted">Next 3 runs:</p>
                                        {cronPreview.map((d, i) => (
                                            <p key={i} className="font-mono text-theme-muted">{d}</p>
                                        ))}
                                    </div>
                                )}
                            </div>
                        </div>
                    )}

                    {activeTab === 'context' && (
                        <div className="space-y-6 animate-in fade-in duration-300">
                            <Textarea
                                label="Contextual Prompt / Follow-up Instruction"
                                placeholder="e.g. Check the API status again..."
                                value={formData.contextualPrompt || ''}
                                onChange={e => updateField('contextualPrompt', e.target.value)}
                                minRows={4}
                                maxRows={8}
                                className="resize-none"
                            />

                            <Input
                                label="Resume Session ID (Optional)"
                                placeholder="e.g. f47ac10b-58cc-4372-a567-0e02b2c3d479"
                                value={formData.resumeSessionId || ''}
                                onChange={e => updateField('resumeSessionId', e.target.value)}
                                className="font-mono text-sm"
                                description="If provided, the scheduled task will wake the agent within this explicit conversation lineage."
                                helpText="Leave empty to start a fresh session each run. Set a session ID to maintain multi-turn context across scheduled executions."
                            />
                        </div>
                    )}
                </form>
                
                <div className="p-4 bg-obsidian-elevated border-t border-obsidian-stroke flex justify-end gap-3 mt-auto">
                    <button type="button" className="btn btn-ghost text-theme-muted hover:text-white" onClick={guardedClose} disabled={submitting}>Cancel</button>
                    <button 
                        type="button" 
                        className="btn bg-agent-blue hover:bg-agent-blue/80 text-white border-none min-w-[150px] shadow-[0_0_15px_rgba(59,130,246,0.2)]" 
                        disabled={submitting} 
                        onClick={handleSubmit}
                    >
                        {submitting ? <span className="loading loading-spinner"></span> : 'Save Schedule'}
                    </button>
                </div>
            </div>
            <div className="modal-backdrop bg-neutral/80" onClick={guardedClose}></div>

            <Dialog
                isOpen={showConfirm}
                setIsOpen={(open) => { if (!open) cancelLeave(); }}
                title="Unsaved Changes"
                content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
                severity="warning"
                confirmLabel="Leave"
                cancelLabel="Stay"
                onConfirm={confirmLeave}
                onCancel={cancelLeave}
            />
        </dialog>
    );
};
