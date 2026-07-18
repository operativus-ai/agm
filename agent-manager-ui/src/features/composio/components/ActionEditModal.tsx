import React, { useState, useEffect } from 'react';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Button } from '../../../shared/components/ui/Button';
import { Input } from '../../../shared/components/ui/Input';
import { Select } from '../../../shared/components/ui/Select';
import { FormFieldWrapper } from '../../../shared/components/ui/FormFieldWrapper';
import { Checkbox } from '../../../shared/components/ui/Checkbox';
import { composioAdminApi } from '../api/composioAdminApi';
import type { ComposioActionConfigResponse } from '../types';
import { TIER_DESCRIPTIONS } from '../types';

interface ActionEditModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved: () => void;
  editing: ComposioActionConfigResponse | null;
}

const TIER_OPTIONS = [
  { value: '1', label: 'Tier 1 — Auto-execute' },
  { value: '2', label: 'Tier 2 — HITL-gated' },
  { value: '3', label: 'Tier 3 — Destructive/Approval' },
];

export const ActionEditModal: React.FC<ActionEditModalProps> = ({
  isOpen,
  onClose,
  onSaved,
  editing,
}) => {
  const [actionName, setActionName] = useState('');
  const [tier, setTier] = useState<'1' | '2' | '3'>('1');
  const [enabled, setEnabled] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (editing) {
      setActionName(editing.actionName);
      setTier(String(editing.tier) as '1' | '2' | '3');
      setEnabled(editing.enabled);
    } else {
      setActionName('');
      setTier('1');
      setEnabled(true);
    }
    setError(null);
  }, [editing, isOpen]);

  const handleSave = async () => {
    if (!actionName.trim()) {
      setError('Action name is required.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      if (editing) {
        await composioAdminApi.updateAction(editing.id, {
          tier: Number(tier) as 1 | 2 | 3,
          enabled,
          version: editing.version,
        });
      } else {
        await composioAdminApi.createAction({
          actionName: actionName.trim(),
          tier: Number(tier) as 1 | 2 | 3,
          enabled,
        });
      }
      onSaved();
      onClose();
    } catch (err: any) {
      setError(err?.message ?? 'Failed to save.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog
      isOpen={isOpen}
      setIsOpen={(open) => { if (!open) onClose(); }}
      title={editing ? 'Edit Action Config' : 'Add Action Config'}
    >
      <div className="space-y-4">
        {!editing && (
          <FormFieldWrapper label="Action Name" required>
            <Input
              value={actionName}
              onChange={(e) => setActionName(e.target.value)}
              placeholder="e.g. GITHUB_CREATE_ISSUE"
              disabled={saving}
            />
          </FormFieldWrapper>
        )}

        <FormFieldWrapper label="Execution Tier" required>
          <Select
            value={tier}
            onChange={(e) => setTier(e.target.value as '1' | '2' | '3')}
            disabled={saving}
            options={TIER_OPTIONS}
          />
          <p className="text-xs text-(--theme-muted) mt-1">
            {TIER_DESCRIPTIONS[Number(tier)]}
          </p>
        </FormFieldWrapper>

        <FormFieldWrapper label="Status">
          <div className="flex items-center gap-2">
            <Checkbox
              checked={enabled}
              onCheckedChange={(v) => setEnabled(v === true)}
              disabled={saving}
            />
            <span className="text-sm">{enabled ? 'Enabled' : 'Disabled'}</span>
          </div>
        </FormFieldWrapper>

        {error && (
          <p className="text-sm text-error-red">{error}</p>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" size="sm" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
          <Button size="sm" onClick={handleSave} disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </div>
    </Dialog>
  );
};
