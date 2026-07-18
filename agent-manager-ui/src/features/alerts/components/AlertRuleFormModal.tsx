import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Input } from '../../../shared/components/ui/Input';
import { Textarea } from '../../../shared/components/ui/Textarea';
import { Select } from '../../../shared/components/ui/Select';
import { Checkbox } from '../../../shared/components/ui/Checkbox';
import { FormFieldWrapper } from '../../../shared/components/ui/FormFieldWrapper';
import { ApiError } from '../../../shared/api/client';
import {
  alertRulesApi,
  type AlertRule,
  type AlertRuleWriteRequest,
  type AlertCondition,
  type AlertSeverity,
} from '../api/alertRulesApi';
import { AlertIntegrationApi } from '../api/alertIntegrationApi';

const SYSTEM_METRICS = [
  'agm.agent.run.failure.rate',
  'agm.agent.run.success.rate',
  'agm.cost.per.run',
  'agm.cache.hit.rate',
] as const;

export interface AlertRuleFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved: () => void;
  existing?: AlertRule | null;
}

type FormState = {
  name: string;
  description: string;
  metricName: string;
  condition: AlertCondition;
  threshold: string; // stored as string for input control; parsed on submit
  windowSeconds: string;
  severity: AlertSeverity;
  enabled: boolean;
  notificationChannel: string;
};

const emptyForm: FormState = {
  name: '',
  description: '',
  metricName: '',
  condition: 'GT',
  threshold: '0',
  windowSeconds: '60',
  severity: 'WARNING',
  enabled: true,
  notificationChannel: '',
};

export const AlertRuleFormModal: React.FC<AlertRuleFormModalProps> = ({
  isOpen,
  onClose,
  onSaved,
  existing,
}) => {
  const [form, setForm] = useState<FormState>(emptyForm);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const { data: integrations = [], isLoading: loadingIntegrations } = useQuery({
    queryKey: ['alert-integrations'],
    queryFn: () => AlertIntegrationApi.list(),
    staleTime: 60_000,
    enabled: isOpen,
  });

  useEffect(() => {
    if (existing) {
      setForm({
        name: existing.name,
        description: existing.description ?? '',
        metricName: existing.metricName,
        condition: existing.condition,
        threshold: String(existing.threshold),
        windowSeconds: String(existing.windowSeconds),
        severity: existing.severity,
        enabled: existing.enabled,
        notificationChannel: existing.notificationChannel ?? '',
      });
    } else {
      setForm(emptyForm);
    }
    setErrors({});
  }, [existing, isOpen]);

  const submit = async () => {
    const e: Record<string, string> = {};
    if (!form.name.trim()) e.name = 'Name is required';
    if (!form.metricName.trim()) e.metricName = 'Metric name is required';

    const threshold = Number(form.threshold);
    if (!Number.isFinite(threshold)) e.threshold = 'Threshold must be a number';

    const windowSeconds = Number(form.windowSeconds);
    if (!Number.isInteger(windowSeconds) || windowSeconds < 1) {
      e.windowSeconds = 'Window must be a positive integer (seconds)';
    }

    setErrors(e);
    if (Object.keys(e).length > 0) return;

    const req: AlertRuleWriteRequest = {
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      metricName: form.metricName.trim(),
      condition: form.condition,
      threshold,
      windowSeconds,
      severity: form.severity,
      enabled: form.enabled,
      notificationChannel: form.notificationChannel.trim() || undefined,
    };

    setSubmitting(true);
    try {
      if (existing) {
        await alertRulesApi.update(existing.id, req);
      } else {
        await alertRulesApi.create(req);
      }
      onSaved();
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.fields) {
        setErrors(err.fields);
      } else if (err instanceof Error) {
        setErrors({ _form: err.message });
      } else {
        setErrors({ _form: 'Failed to save rule' });
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      isOpen={isOpen}
      setIsOpen={(open) => !open && onClose()}
      title={existing ? `Edit rule: ${existing.name}` : 'Create alert rule'}
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

        <FormFieldWrapper label="Name" error={errors.name}>
          <Input
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            placeholder="e.g. high-run-error-rate"
            disabled={submitting}
          />
        </FormFieldWrapper>

        <FormFieldWrapper label="Description (optional)">
          <Textarea
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            placeholder="Short context about what this rule alerts on"
            rows={2}
            disabled={submitting}
          />
        </FormFieldWrapper>

        <FormFieldWrapper label="Metric name" error={errors.metricName}>
          <Select
            value={form.metricName}
            onValueChange={(v) => setForm({ ...form, metricName: v })}
            disabled={submitting}
            placeholder="Select a metric…"
            options={SYSTEM_METRICS.map(m => ({ value: m, label: m }))}
            className="font-mono text-xs"
          />
        </FormFieldWrapper>

        <div className="grid grid-cols-2 gap-3">
          <FormFieldWrapper label="Condition">
            <Select
              value={form.condition}
              onValueChange={(v) => setForm({ ...form, condition: v as AlertCondition })}
              disabled={submitting}
              options={[
                { label: '> Greater than', value: 'GT' },
                { label: '≥ Greater or equal', value: 'GTE' },
                { label: '< Less than', value: 'LT' },
                { label: '≤ Less or equal', value: 'LTE' },
                { label: '= Equal', value: 'EQ' },
              ]}
            />
          </FormFieldWrapper>

          <FormFieldWrapper label="Threshold" error={errors.threshold}>
            <Input
              type="number"
              value={form.threshold}
              onChange={(e) => setForm({ ...form, threshold: e.target.value })}
              disabled={submitting}
              step="0.01"
            />
          </FormFieldWrapper>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <FormFieldWrapper label="Window (seconds)" error={errors.windowSeconds}>
            <Input
              type="number"
              value={form.windowSeconds}
              onChange={(e) => setForm({ ...form, windowSeconds: e.target.value })}
              disabled={submitting}
              min={1}
            />
          </FormFieldWrapper>

          <FormFieldWrapper label="Severity">
            <Select
              value={form.severity}
              onValueChange={(v) => setForm({ ...form, severity: v as AlertSeverity })}
              disabled={submitting}
              options={[
                { label: 'INFO', value: 'INFO' },
                { label: 'WARNING', value: 'WARNING' },
                { label: 'CRITICAL', value: 'CRITICAL' },
              ]}
            />
          </FormFieldWrapper>
        </div>

        <FormFieldWrapper
          label="Notification channel (optional)"
          helpText="Select a configured alert integration to route notifications."
        >
          <Select
            value={form.notificationChannel || '__none__'}
            onValueChange={(v) => setForm({ ...form, notificationChannel: v === '__none__' ? '' : v })}
            disabled={submitting || loadingIntegrations}
            options={[
              { value: '__none__', label: '— None —' },
              ...integrations.map(i => ({ value: i.name, label: i.name })),
            ]}
          />
          {!loadingIntegrations && integrations.length === 0 && (
            <p className="text-xs text-warning mt-1">
              No integrations configured.{' '}
              <Link to="/admin/alert-integrations" className="underline hover:text-warning/80">
                Add one →
              </Link>
            </p>
          )}
        </FormFieldWrapper>

        <div className="flex items-center gap-2 pt-2">
          <Checkbox
            checked={form.enabled}
            onCheckedChange={(c: boolean) => setForm({ ...form, enabled: c })}
            disabled={submitting}
            id="alert-rule-enabled"
          />
          <label htmlFor="alert-rule-enabled" className="text-sm cursor-pointer select-none">
            Enabled
          </label>
        </div>
      </div>
    </Dialog>
  );
};
