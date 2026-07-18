import React, { useEffect, useState } from 'react';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Input } from '../../../shared/components/ui/Input';
import { Textarea } from '../../../shared/components/ui/Textarea';
import { Checkbox } from '../../../shared/components/ui/Checkbox';
import { FormFieldWrapper } from '../../../shared/components/ui/FormFieldWrapper';
import { ApiError } from '../../../shared/api/client';
import { skillsApi, type Skill, type SkillWriteRequest } from '../api/skillsApi';

export interface SkillFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved: () => void;
  existing?: Skill | null;
}

type FormState = {
  name: string;
  description: string;
  systemPromptSnippet: string;
  allowedTools: string; // one tool per line or comma-separated; parsed on submit
  active: boolean;
};

const emptyForm: FormState = {
  name: '',
  description: '',
  systemPromptSnippet: '',
  allowedTools: '',
  active: true,
};

// Split on newlines or commas, trim, drop blanks, de-dupe.
const parseTools = (raw: string): string[] =>
  Array.from(new Set(raw.split(/[\n,]/).map(t => t.trim()).filter(Boolean)));

export const SkillFormModal: React.FC<SkillFormModalProps> = ({ isOpen, onClose, onSaved, existing }) => {
  const [form, setForm] = useState<FormState>(emptyForm);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (existing) {
      setForm({
        name: existing.name,
        description: existing.description ?? '',
        systemPromptSnippet: existing.systemPromptSnippet ?? '',
        allowedTools: (existing.allowedTools ?? []).join('\n'),
        active: existing.active,
      });
    } else {
      setForm(emptyForm);
    }
    setErrors({});
  }, [existing, isOpen]);

  const submit = async () => {
    const e: Record<string, string> = {};
    if (!form.name.trim()) e.name = 'Name is required';
    setErrors(e);
    if (Object.keys(e).length > 0) return;

    const req: SkillWriteRequest = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      systemPromptSnippet: form.systemPromptSnippet.trim() || undefined,
      allowedTools: parseTools(form.allowedTools),
      active: form.active,
    };

    setSubmitting(true);
    try {
      if (existing) {
        await skillsApi.update(existing.id, req);
      } else {
        await skillsApi.create(req);
      }
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.fields) {
        setErrors(err.fields);
      } else if (err instanceof Error) {
        setErrors({ _form: err.message });
      } else {
        setErrors({ _form: 'Failed to save skill' });
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      isOpen={isOpen}
      setIsOpen={(open) => !open && onClose()}
      title={existing ? `Edit skill: ${existing.name}` : 'Create skill'}
      onConfirm={submit}
      onCancel={onClose}
      confirmLabel={submitting ? 'Saving…' : (existing ? 'Save' : 'Create')}
      canBeCanceled
      shouldCloseOnConfirm={false}
    >
      <div className="space-y-4">
        {errors._form && (
          <div className="text-sm text-error bg-error/10 border border-error/30 rounded px-3 py-2">
            {errors._form}
          </div>
        )}

        <FormFieldWrapper label="Name" error={errors.name}>
          <Input
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            placeholder="e.g. web-research"
            disabled={submitting}
          />
        </FormFieldWrapper>

        <FormFieldWrapper label="Description (optional)" error={errors.description}>
          <Textarea
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            placeholder="Short summary of what this skill equips an agent to do"
            rows={2}
            disabled={submitting}
          />
        </FormFieldWrapper>

        <FormFieldWrapper
          label="System prompt snippet (optional)"
          error={errors.systemPromptSnippet}
          helpText="Injected into the system prompt of any agent this skill is attached to."
        >
          <Textarea
            value={form.systemPromptSnippet}
            onChange={(e) => setForm({ ...form, systemPromptSnippet: e.target.value })}
            placeholder="You can search the web. Cite sources."
            rows={4}
            disabled={submitting}
          />
        </FormFieldWrapper>

        <FormFieldWrapper
          label="Allowed tools (optional)"
          error={errors.allowedTools}
          helpText="One tool name per line (or comma-separated). Restricts which tools the skill grants."
        >
          <Textarea
            value={form.allowedTools}
            onChange={(e) => setForm({ ...form, allowedTools: e.target.value })}
            placeholder={'web_search\nfetch_url'}
            rows={3}
            disabled={submitting}
            className="font-mono text-xs"
          />
        </FormFieldWrapper>

        <div className="flex items-center gap-2 pt-1">
          <Checkbox
            checked={form.active}
            onCheckedChange={(c: boolean) => setForm({ ...form, active: c })}
            disabled={submitting}
            id="skill-active"
          />
          <label htmlFor="skill-active" className="text-sm cursor-pointer select-none">
            Active
          </label>
        </div>
      </div>
    </Dialog>
  );
};
