import React, { useState, useEffect } from 'react';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Button } from '../../../shared/components/ui/Button';
import { Input } from '../../../shared/components/ui/Input';
import { FormFieldWrapper } from '../../../shared/components/ui/FormFieldWrapper';
import { composioAdminApi } from '../api/composioAdminApi';
import type { ComposioConnectionConfigResponse } from '../types';

interface ConnectionEditModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved: () => void;
  existing: ComposioConnectionConfigResponse | null;
}

export const ConnectionEditModal: React.FC<ConnectionEditModalProps> = ({
  isOpen,
  onClose,
  onSaved,
  existing,
}) => {
  const [connectionId, setConnectionId] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setConnectionId(existing?.connectionId ?? '');
    setError(null);
  }, [existing, isOpen]);

  const handleSave = async () => {
    if (!connectionId.trim()) {
      setError('Connection ID is required.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await composioAdminApi.upsertConnection({
        connectionId: connectionId.trim(),
        version: existing?.version ?? null,
      });
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
      title={existing ? 'Update Connection' : 'Set Connection'}
    >
      <div className="space-y-4">
        <FormFieldWrapper label="Composio Connection ID" required>
          <Input
            value={connectionId}
            onChange={(e) => setConnectionId(e.target.value)}
            placeholder="e.g. abc123-github-connection"
            disabled={saving}
          />
          <p className="text-xs text-(--theme-muted) mt-1">
            The connection ID from your Composio dashboard for this org.
          </p>
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
