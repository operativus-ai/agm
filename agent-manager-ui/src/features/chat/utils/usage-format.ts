import type { UsageSummary } from '../types';

/** Thousands-separated token count, e.g. 1500 → "1,500". */
export function formatTokenCount(n: number): string {
  return n.toLocaleString('en-US');
}

/** USD with precision scaled to magnitude: sub-cent costs need 4 decimals to be non-zero. */
export function formatUsd(usd: number): string {
  if (usd > 0 && usd < 0.01) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

/** Total tokens, falling back to the sum of components when the backend omitted the total. */
export function totalTokensOf(u: UsageSummary): number {
  return u.totalTokens ?? ((u.inputTokens ?? 0) + (u.outputTokens ?? 0) + (u.reasoningTokens ?? 0));
}
