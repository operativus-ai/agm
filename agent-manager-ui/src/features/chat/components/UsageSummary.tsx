import React from 'react';
import type { UsageSummary } from '../types';
import { formatTokenCount, formatUsd, totalTokensOf } from '../utils/usage-format';

/**
 * Renders the terminal per-run usage total (tokens + USD cost + model) carried by the METRICS
 * stream frame. Sits in the message footer alongside the timestamp/duration. Suppressed when no
 * usage was captured so we never show an empty "0 tokens" badge.
 */
export const UsageSummaryBadge: React.FC<{ usage?: UsageSummary }> = ({ usage }) => {
  if (!usage) return null;
  const total = totalTokensOf(usage);
  if (total <= 0) return null;

  const parts: string[] = [`${formatTokenCount(total)} tokens`];
  if (usage.costUsd && usage.costUsd > 0) parts.push(formatUsd(usage.costUsd));
  if (usage.model) parts.push(usage.model);

  const tooltip = [
    usage.inputTokens != null ? `in ${formatTokenCount(usage.inputTokens)}` : null,
    usage.outputTokens != null ? `out ${formatTokenCount(usage.outputTokens)}` : null,
    usage.reasoningTokens ? `reasoning ${formatTokenCount(usage.reasoningTokens)}` : null,
    usage.llmCalls != null ? `${usage.llmCalls} LLM call${usage.llmCalls === 1 ? '' : 's'}` : null,
  ].filter(Boolean).join(' • ');

  return (
    <span className="font-mono" title={tooltip || undefined} data-testid="usage-summary">
      • {parts.join(' · ')}
    </span>
  );
};
