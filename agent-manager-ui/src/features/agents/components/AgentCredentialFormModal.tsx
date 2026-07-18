import React, { useEffect, useState } from 'react';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { NO_AUTOFILL } from '../../../shared/utils/noAutofill';
import { Input } from '../../../shared/components/ui/Input';
import { Select } from '../../../shared/components/ui/Select';
import { Checkbox } from '../../../shared/components/ui/Checkbox';
import { FormFieldWrapper } from '../../../shared/components/ui/FormFieldWrapper';
import { ApiError } from '../../../shared/api/client';
import {
  agentCredentialsApi,
  type CredentialType,
} from '../api/agentCredentialsApi';

export interface AgentCredentialFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved: () => void;
  agentId: string;
}

type FormState = {
  credentialType: CredentialType;
  providerName: string;
  encryptedSecret: string;
  scopes: string;
  tokenEndpoint: string;
  clientId: string;
  enabled: boolean;
};

const emptyForm: FormState = {
  credentialType: 'API_KEY',
  providerName: '',
  encryptedSecret: '',
  scopes: '',
  tokenEndpoint: '',
  clientId: '',
  enabled: true,
};

/**
 * Create-only modal. Intentionally NOT used for editing the secret of an
 * existing credential — per U040 security policy, rotate by delete+create
 * rather than read-and-edit (avoids plaintext roundtrip).
 */
export const AgentCredentialFormModal: React.FC<AgentCredentialFormModalProps> = ({
  isOpen,
  onClose,
  onSaved,
  agentId,
}) => {
  const [form, setForm] = useState<FormState>(emptyForm);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setForm(emptyForm);
      setErrors({});
    }
  }, [isOpen]);

  const isOAuth = form.credentialType === 'OAUTH2';

  const submit = async () => {
    const e: Record<string, string> = {};
    if (!form.providerName.trim()) e.providerName = 'Provider name is required';
    if (!form.encryptedSecret.trim()) e.encryptedSecret = 'Secret is required';
    if (isOAuth) {
      if (!form.tokenEndpoint.trim()) e.tokenEndpoint = 'Token endpoint is required for OAUTH2';
      if (!form.clientId.trim()) e.clientId = 'Client ID is required for OAUTH2';
    }
    setErrors(e);
    if (Object.keys(e).length > 0) return;

    setSubmitting(true);
    try {
      await agentCredentialsApi.create(agentId, {
        credentialType: form.credentialType,
        providerName: form.providerName.trim(),
        encryptedSecret: form.encryptedSecret,
        scopes: isOAuth ? (form.scopes.trim() || undefined) : undefined,
        tokenEndpoint: isOAuth ? form.tokenEndpoint.trim() : undefined,
        clientId: isOAuth ? form.clientId.trim() : undefined,
        enabled: form.enabled,
      });
      // Wipe local secret state immediately; never round-trip.
      setForm((f) => ({ ...f, encryptedSecret: '' }));
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.fields) {
        setErrors(err.fields);
      } else if (err instanceof Error) {
        setErrors({ _form: err.message });
      } else {
        setErrors({ _form: 'Failed to create credential' });
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      isOpen={isOpen}
      setIsOpen={(open) => !open && onClose()}
      title="Add credential"
      onConfirm={submit}
      onCancel={onClose}
      canBeCanceled
      shouldCloseOnConfirm={false}
    >
      <div className="space-y-4">
        {errors._form && (
          <div className="text-sm text-error bg-error/10 border border-error/30 rounded px-3 py-2">
            {errors._form}
          </div>
        )}

        <div className="text-xs text-theme-muted bg-obsidian-elevated/50 border border-obsidian-stroke/50 rounded px-3 py-2">
          <strong>Security:</strong> secrets are never displayed after creation.
          To rotate, delete the credential and create a new one with fresh material.
        </div>

        <FormFieldWrapper label="Type">
          <Select
            value={form.credentialType}
            onValueChange={(v) => setForm({ ...form, credentialType: v as CredentialType })}
            disabled={submitting}
            options={[
              { label: 'API Key', value: 'API_KEY' },
              { label: 'Bearer Token', value: 'BEARER' },
              { label: 'JWT', value: 'JWT' },
              { label: 'OAuth 2.0', value: 'OAUTH2' },
            ]}
          />
        </FormFieldWrapper>

        <FormFieldWrapper label="Provider name" error={errors.providerName}>
          <Input
            value={form.providerName}
            onChange={(e) => setForm({ ...form, providerName: e.target.value })}
            placeholder="e.g. stripe, github, slack"
            disabled={submitting}
          />
        </FormFieldWrapper>

        <FormFieldWrapper
          label="Secret"
          error={errors.encryptedSecret}
          helpText="This value is submitted once and never shown again. Store it in your password manager before saving."
        >
          <Input
            type="password"
            {...NO_AUTOFILL}
            value={form.encryptedSecret}
            onChange={(e) => setForm({ ...form, encryptedSecret: e.target.value })}
            placeholder={
              form.credentialType === 'OAUTH2'
                ? 'OAuth client secret'
                : form.credentialType === 'API_KEY'
                ? 'sk-... / paste API key'
                : 'Token value'
            }
            disabled={submitting}
            autoComplete="new-password"
          />
        </FormFieldWrapper>

        {isOAuth && (
          <>
            <FormFieldWrapper label="Token endpoint" error={errors.tokenEndpoint}>
              <Input
                value={form.tokenEndpoint}
                onChange={(e) => setForm({ ...form, tokenEndpoint: e.target.value })}
                placeholder="https://auth.example.com/oauth/token"
                disabled={submitting}
                className="font-mono text-xs"
              />
            </FormFieldWrapper>

            <FormFieldWrapper label="Client ID" error={errors.clientId}>
              <Input
                value={form.clientId}
                onChange={(e) => setForm({ ...form, clientId: e.target.value })}
                placeholder="OAuth client identifier"
                disabled={submitting}
                className="font-mono text-xs"
              />
            </FormFieldWrapper>

            <FormFieldWrapper
              label="Scopes (comma-separated, optional)"
              helpText="OAuth scopes granted when the secret is minted (e.g. read:user,repo)"
            >
              <Input
                value={form.scopes}
                onChange={(e) => setForm({ ...form, scopes: e.target.value })}
                placeholder="read:user, write:issues"
                disabled={submitting}
              />
            </FormFieldWrapper>
          </>
        )}

        <div className="flex items-center gap-2 pt-2">
          <Checkbox
            checked={form.enabled}
            onCheckedChange={(c: boolean) => setForm({ ...form, enabled: c })}
            disabled={submitting}
            id="credential-enabled"
          />
          <label htmlFor="credential-enabled" className="text-sm cursor-pointer select-none">
            Enabled
          </label>
        </div>
      </div>
    </Dialog>
  );
};
