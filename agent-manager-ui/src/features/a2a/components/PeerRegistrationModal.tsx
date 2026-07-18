import React, { useState } from 'react';
import { useRegisterPeer } from '../hooks/useA2a';
import { Button } from '../../../shared/components/ui/Button';
import { NO_AUTOFILL } from '../../../shared/utils/noAutofill';
import { Typography } from '../../../shared/components/ui/Typography';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { LuPlus } from 'react-icons/lu';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';

interface PeerRegistrationModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const PeerRegistrationModal: React.FC<PeerRegistrationModalProps> = ({ isOpen, onClose }) => {
  const [alias, setAlias] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [remoteAgentId, setRemoteAgentId] = useState('');
  const [apiKey, setApiKey] = useState('');

  const registerPeer = useRegisterPeer();
  const { markDirty, resetDirty, guardedClose, showConfirm, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);

  if (!isOpen) return null;

  const handleFieldChange = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setter(e.target.value);
    markDirty();
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await registerPeer.mutateAsync({ remoteAgentId, baseUrl, alias, apiKey });
      setAlias('');
      setBaseUrl('');
      setRemoteAgentId('');
      setApiKey('');
      resetDirty();
      onClose();
    } catch {
      // Error handled by mutation state
    }
  };

  const resetAndClose = () => {
    setAlias('');
    setBaseUrl('');
    setRemoteAgentId('');
    setApiKey('');
    registerPeer.reset();
    resetDirty();
    onClose();
  };

  const handleGuardedClose = () => {
    // Check dirty before resetting
    guardedClose();
  };

  return (
    <dialog className="modal modal-open">
      <div className="modal-box w-11/12 max-w-lg bg-obsidian-raised border border-obsidian-stroke">
        <h3 className="font-bold text-lg mb-1 text-theme-foreground">Register Remote Peer</h3>
        <Typography.Text variant="muted" className="text-sm mb-4 block">
          Connect a trusted external A2A agent to the network mesh.
        </Typography.Text>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="form-control">
            <label className="label"><span className="label-text text-theme-muted">Alias</span></label>
            <input
              type="text"
              className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke text-theme-foreground"
              placeholder="e.g., agno-prod-cluster"
              value={alias}
              onChange={handleFieldChange(setAlias)}
              required
              disabled={registerPeer.isPending}
            />
          </div>

          <div className="form-control">
            <label className="label"><span className="label-text text-theme-muted">Remote Agent ID</span></label>
            <input
              type="text"
              className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke text-theme-foreground font-mono text-sm"
              placeholder="e.g., agno-analyst-v2"
              value={remoteAgentId}
              onChange={handleFieldChange(setRemoteAgentId)}
              required
              disabled={registerPeer.isPending}
            />
          </div>

          <div className="form-control">
            <label className="label"><span className="label-text text-theme-muted">Base URL</span></label>
            <input
              type="url"
              className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke text-theme-foreground font-mono text-sm"
              placeholder="https://remote-agm.example.com"
              value={baseUrl}
              onChange={handleFieldChange(setBaseUrl)}
              required
              disabled={registerPeer.isPending}
            />
            <label className="label">
              <span className="label-text-alt text-theme-muted">Private/loopback addresses are blocked by SSRF guards.</span>
            </label>
          </div>

          <div className="form-control">
            <label className="label"><span className="label-text text-theme-muted">Outbound API Key</span></label>
            <input
              type="password"
              {...NO_AUTOFILL}
              className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke text-theme-foreground font-mono text-sm"
              placeholder="X-A2A-Api-Key credential"
              value={apiKey}
              onChange={handleFieldChange(setApiKey)}
              disabled={registerPeer.isPending}
            />
          </div>

          {registerPeer.isError && (
            <div className="p-3 bg-error-red/10 text-error-red rounded-md text-sm border border-error-red/20">
              {(registerPeer.error as Error)?.message || 'Registration failed. Check the base URL and try again.'}
            </div>
          )}

          <div className="flex justify-end gap-2 pt-4 border-t border-obsidian-stroke/30">
            <Button type="button" variant="ghost" onClick={handleGuardedClose} disabled={registerPeer.isPending}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={registerPeer.isPending || !alias.trim() || !baseUrl.trim() || !remoteAgentId.trim()}
            >
              {registerPeer.isPending ? <span className="loading loading-spinner loading-sm" /> : <LuPlus className="mr-1.5 w-4 h-4" />}
              Register Peer
            </Button>
          </div>
        </form>
      </div>
      <form method="dialog" className="modal-backdrop">
        <button onClick={handleGuardedClose}>close</button>
      </form>

      <Dialog
        isOpen={showConfirm}
        setIsOpen={(open) => { if (!open) cancelLeave(); }}
        title="Unsaved Changes"
        content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
        severity="warning"
        confirmLabel="Leave"
        cancelLabel="Stay"
        onConfirm={() => { resetAndClose(); }}
        onCancel={cancelLeave}
      />
    </dialog>
  );
};
