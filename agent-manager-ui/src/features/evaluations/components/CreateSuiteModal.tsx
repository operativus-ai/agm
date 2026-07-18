import React, { useState } from 'react';
import { useEvaluationStore } from '../store/evaluationStore';
import { Typography } from '../../../shared/components/ui/Typography';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';

interface CreateSuiteModalProps {
  onClose: () => void;
}

export const CreateSuiteModal: React.FC<CreateSuiteModalProps> = ({ onClose }) => {
  const { createSuite, isLoading } = useEvaluationStore();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    await createSuite({ name, description });
    resetDirty();
    onClose();
  };

  return (
    <div className="modal modal-open modal-bottom sm:modal-middle">
      <div className="modal-box bg-obsidian-raised border border-obsidian-stroke">
        <Typography.Heading level={3} className="text-lg font-bold mb-4">
          Create Evaluation Suite
        </Typography.Heading>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="form-control">
            <label className="label">
              <span className="label-text">Suite Name</span>
            </label>
            <input
              type="text"
              className="input input-bordered w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
              placeholder="e.g., Code Review Agent Precision Test"
              value={name}
              onChange={e => { setName(e.target.value); markDirty(); }}
              required
              autoFocus
            />
          </div>

          <div className="form-control">
            <label className="label">
              <span className="label-text">Description</span>
            </label>
            <textarea
              className="textarea textarea-bordered h-24 w-full bg-obsidian-surface border-obsidian-stroke focus:border-agent-blue"
              placeholder="Optional description of what this suite measures."
              value={description}
              onChange={e => { setDescription(e.target.value); markDirty(); }}
            />
          </div>

          <div className="modal-action">
            <button type="button" className="btn" onClick={guardedClose} disabled={isLoading}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isLoading || !name.trim()}>
              {isLoading ? 'Creating...' : 'Create Suite'}
            </button>
          </div>
        </form>
      </div>
      <div className="modal-backdrop" onClick={guardedClose}>
        <button className="cursor-default">close</button>
      </div>

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
    </div>
  );
};
