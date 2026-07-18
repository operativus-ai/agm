import React, { useState } from 'react';
import { piiPolicyApi } from '../api/piiPolicyApi';
import type { PiiPolicyCreateRequest } from '../types/pii.types';
import { Input } from '../../../shared/components/ui/Input';
import { Select } from '../../../shared/components/ui/Select';
import { Textarea } from '../../../shared/components/ui/Textarea';
import { Alert } from '../../../shared/components/ui/Alert';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';

interface CreatePiiPolicyModalProps {
    isOpen: boolean;
    onClose: () => void;
    onCreated: () => void;
}

const INITIAL_FORM: PiiPolicyCreateRequest = {
    name: '',
    description: '',
    patternType: 'REGEX',
    pattern: '',
    scrubStrategy: 'REDACT',
    enabled: true,
    taxonomicCategory: 'UNCATEGORIZED',
    complianceFramework: 'STANDARD',
};

export const CreatePiiPolicyModal: React.FC<CreatePiiPolicyModalProps> = ({ isOpen, onClose, onCreated }) => {
    const [formData, setFormData] = useState<PiiPolicyCreateRequest>({ ...INITIAL_FORM });
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);

    if (!isOpen) return null;

    const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
        const { name, value, type } = e.target;
        const val = type === 'checkbox' ? (e.target as HTMLInputElement).checked : value;
        setFormData(prev => ({ ...prev, [name]: val }));
        markDirty();
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);
        setSaving(true);
        try {
            await piiPolicyApi.createPolicy(formData);
            setFormData({ ...INITIAL_FORM });
            onCreated();
            resetDirty();
            onClose();
        } catch (err: any) {
            setError(err.message || 'Failed to create PII policy');
        } finally {
            setSaving(false);
        }
    };

    const handleCancel = () => {
        guardedClose();
    };

    return (
        <dialog className="modal modal-open">
            <div className="modal-box w-full max-w-lg bg-obsidian-raised border border-obsidian-stroke">
                <h3 className="font-bold text-lg mb-4 text-theme-foreground">Create PII Detection Policy</h3>

                {error && (
                    <Alert severity="error" className="mb-4">{error}</Alert>
                )}

                <form onSubmit={handleSubmit} className="flex flex-col gap-4">
                    <Input
                        label="Policy Name"
                        name="name"
                        value={formData.name}
                        onChange={handleChange}
                        required
                        placeholder="e.g. US_SSN"
                    />

                    <Textarea
                        label="Description"
                        name="description"
                        value={formData.description || ''}
                        onChange={handleChange}
                        placeholder="Brief description of what this rule detects"
                        minRows={2}
                        maxRows={4}
                        className="resize-none"
                    />

                    <div className="grid grid-cols-2 gap-4">
                        <Select
                            label="Pattern Type"
                            name="patternType"
                            value={formData.patternType}
                            onChange={handleChange}
                            options={[
                                { label: 'REGEX', value: 'REGEX' },
                                { label: 'LUHN (Credit Card)', value: 'LUHN' },
                            ]}
                        />

                        <Select
                            label="Scrub Strategy"
                            name="scrubStrategy"
                            value={formData.scrubStrategy}
                            onChange={handleChange}
                            options={[
                                { label: 'REDACT (Replace with ***)', value: 'REDACT' },
                                { label: 'FPE (Format-Preserving Encryption)', value: 'FPE' },
                            ]}
                        />
                    </div>

                    <div className="grid grid-cols-2 gap-4">
                        <Select
                            label="Taxonomy Category"
                            name="taxonomicCategory"
                            value={formData.taxonomicCategory}
                            onChange={handleChange}
                            options={[
                                { label: 'UNCATEGORIZED', value: 'UNCATEGORIZED' },
                                { label: 'FINANCIAL', value: 'FINANCIAL' },
                                { label: 'MEDICAL', value: 'MEDICAL' },
                                { label: 'IDENTIFICATION', value: 'IDENTIFICATION' },
                                { label: 'BIOMETRIC', value: 'BIOMETRIC' },
                                { label: 'LOCATION', value: 'LOCATION' },
                            ]}
                        />

                        <Select
                            label="Compliance Framework"
                            name="complianceFramework"
                            value={formData.complianceFramework}
                            onChange={handleChange}
                            options={[
                                { label: 'STANDARD', value: 'STANDARD' },
                                { label: 'HIPAA', value: 'HIPAA' },
                                { label: 'PCI DSS', value: 'PCI_DSS' },
                                { label: 'GDPR', value: 'GDPR' },
                                { label: 'CCPA', value: 'CCPA' },
                            ]}
                        />
                    </div>

                    <Input
                        label="Detection Pattern"
                        name="pattern"
                        value={formData.pattern}
                        onChange={handleChange}
                        className="font-mono text-sm"
                        placeholder={formData.patternType === 'LUHN' ? '(auto-detected)' : 'e.g. \\b\\d{3}-\\d{2}-\\d{4}\\b'}
                        required={formData.patternType === 'REGEX'}
                        disabled={formData.patternType === 'LUHN'}
                        description={formData.patternType === 'LUHN' ? 'Not required for Luhn checks' : 'Java-compatible regex'}
                    />

                    <div className="form-control">
                        <label className="cursor-pointer flex flex-col gap-2 p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors">
                            <span className="label-text font-bold text-theme-muted tracking-wider uppercase text-xs">Enable immediately</span>
                            <input
                                type="checkbox"
                                name="enabled"
                                checked={formData.enabled}
                                onChange={handleChange}
                                className="toggle toggle-success"
                            />
                        </label>
                    </div>

                    <div className="modal-action">
                        <button type="button" className="btn bg-obsidian-surface border-obsidian-stroke text-theme-foreground hover:border-agent-blue/50" onClick={handleCancel} disabled={saving}>Cancel</button>
                        <button type="submit" className="btn btn-warning" disabled={saving}>
                            {saving ? <span className="loading loading-spinner loading-sm"></span> : 'Create Policy'}
                        </button>
                    </div>
                </form>
            </div>
            <form method="dialog" className="modal-backdrop">
                <button onClick={handleCancel}>close</button>
            </form>

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
