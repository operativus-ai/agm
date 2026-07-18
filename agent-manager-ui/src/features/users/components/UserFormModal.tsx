import { ROLES, ASSIGNABLE_ROLES } from '../../../shared/constants/roles';
import React, { useState, useEffect } from 'react';
import type { UserAdmin, UserCreateRequest, UserUpdateRequest } from '../../../shared/types/api';
import { Typography } from '../../../shared/components/ui/Typography';
import { Alert } from '../../../shared/components/ui/Alert';
import { Input } from '../../../shared/components/ui/Input';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { useUnsavedChangesGuard } from '../../../shared/hooks/useUnsavedChangesGuard';

const ALL_ROLES = ASSIGNABLE_ROLES;

interface UserFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (id: string | null, data: UserCreateRequest | UserUpdateRequest) => Promise<void>;
  user?: UserAdmin | null;
}

export const UserFormModal: React.FC<UserFormModalProps> = ({ isOpen, onClose, onSave, user }) => {
  const isEditing = !!user;
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [roles, setRoles] = useState<string[]>([ROLES.VIEWER]);
  const [disabled, setDisabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { markDirty, resetDirty, guardedClose, showConfirm, confirmLeave, cancelLeave } = useUnsavedChangesGuard(onClose, isOpen);

  useEffect(() => {
    if (user) {
      setEmail(user.email);
      setRoles(user.roles);
      setDisabled(user.disabled);
      setUsername(user.username);
    } else {
      setUsername('');
      setEmail('');
      setPassword('');
      setRoles([ROLES.VIEWER]);
      setDisabled(false);
    }
    setError(null);
  }, [user, isOpen]);

  if (!isOpen) return null;

  const toggleRole = (role: string) => {
    setRoles(prev => prev.includes(role) ? prev.filter(r => r !== role) : [...prev, role]);
    markDirty();
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setLoading(true);
      setError(null);
      if (isEditing) {
        const req: UserUpdateRequest = { email, roles, disabled };
        await onSave(user!.id, req);
      } else {
        if (!password) { setError('Password is required'); return; }
        const req: UserCreateRequest = { username, email, password, roles };
        await onSave(null, req);
      }
      resetDirty();
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to save user');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal modal-open">
      <div className="modal-box w-11/12 max-w-lg bg-obsidian-raised border border-obsidian-stroke p-0 overflow-hidden flex flex-col">
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-obsidian-stroke shrink-0">
          <Typography.Heading level={3}>{isEditing ? 'Edit User' : 'Create User'}</Typography.Heading>
          <button type="button" className="btn btn-sm btn-circle bg-obsidian-surface border-obsidian-stroke text-theme-foreground hover:border-agent-blue/50" onClick={guardedClose}>✕</button>
        </div>

        {error && <Alert severity="error" className="mx-6 mt-4">{error}</Alert>}

        <form onSubmit={handleSubmit} className="space-y-4 overflow-y-auto flex-1 px-6 py-4">
          {!isEditing && (
            <Input label="Username" name="username" value={username} onChange={e => { setUsername(e.target.value); markDirty(); }} required placeholder="e.g. jsmith" />
          )}
          <Input label="Email" type="email" name="email" value={email} onChange={e => { setEmail(e.target.value); markDirty(); }} required placeholder="user@example.com" />
          {!isEditing && (
            <Input label="Password" type="password" name="password" value={password} onChange={e => { setPassword(e.target.value); markDirty(); }} required placeholder="Minimum 8 characters" />
          )}

          <div>
            <label className="block text-xs font-bold uppercase tracking-wider text-theme-muted mb-2">Roles</label>
            <div className="grid grid-cols-1 gap-2">
              {ALL_ROLES.map(role => (
                <label key={role} className="cursor-pointer flex items-center gap-3 p-3 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors">
                  <input type="checkbox" className="checkbox checkbox-sm checkbox-primary" checked={roles.includes(role)} onChange={() => toggleRole(role)} />
                  <span className="text-sm text-theme-foreground">{role}</span>
                </label>
              ))}
            </div>
          </div>

          {isEditing && (
            <label className="cursor-pointer flex items-center gap-3 p-4 border border-obsidian-stroke rounded-lg bg-obsidian-surface hover:border-agent-blue/50 transition-colors">
              <input type="checkbox" className="checkbox checkbox-sm checkbox-error" checked={disabled} onChange={e => { setDisabled(e.target.checked); markDirty(); }} />
              <div>
                <span className="block text-sm font-medium text-theme-foreground">Account Disabled</span>
                <span className="block text-xs text-theme-muted">Disabled users cannot log in but are not deleted.</span>
              </div>
            </label>
          )}
        </form>

        <div className="px-6 py-4 bg-obsidian-elevated border-t border-obsidian-stroke flex justify-end gap-2 shrink-0">
          <button type="button" className="btn btn-ghost btn-sm" onClick={guardedClose} disabled={loading}>Cancel</button>
          <button type="button" className="btn btn-primary btn-sm" onClick={handleSubmit} disabled={loading}>
            {loading ? <span className="loading loading-spinner loading-sm"></span> : isEditing ? 'Save Changes' : 'Create User'}
          </button>
        </div>
      </div>
      <div className="modal-backdrop" onClick={guardedClose}></div>

      <Dialog isOpen={showConfirm} setIsOpen={open => { if (!open) cancelLeave(); }} title="Unsaved Changes" content="You have unsaved changes. Are you sure you want to leave?" severity="warning" confirmLabel="Leave" cancelLabel="Stay" onConfirm={confirmLeave} onCancel={cancelLeave} />
    </div>
  );
};
