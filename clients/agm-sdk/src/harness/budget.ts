// Token/cost accumulator with a hard ceiling (NFR-7). T2 scenarios feed their
// terminal METRICS usage in; once the ceiling is crossed the runner stops
// launching further LLM work so a test loop can't burn the org budget it tests.

import type { UsageSummary } from '../types.js';

export class Budget {
  private spentUsd = 0;
  private tokens = 0;
  private runs = 0;

  /** ceilingUsd <= 0 means "no ceiling". */
  constructor(private readonly ceilingUsd: number) {}

  record(usage?: UsageSummary): void {
    if (!usage) return;
    this.runs++;
    if (typeof usage.costUsd === 'number') this.spentUsd += usage.costUsd;
    if (typeof usage.totalTokens === 'number') this.tokens += usage.totalTokens;
  }

  get usd(): number {
    return this.spentUsd;
  }
  get totalTokens(): number {
    return this.tokens;
  }
  get runCount(): number {
    return this.runs;
  }

  exceeded(): boolean {
    return this.ceilingUsd > 0 && this.spentUsd >= this.ceilingUsd;
  }

  /** Throw if the ceiling has been crossed — called by the runner before a T2 scenario. */
  guard(): void {
    if (this.exceeded()) {
      throw new BudgetExceededError(
        `Budget ceiling $${this.ceilingUsd.toFixed(2)} reached (spent $${this.spentUsd.toFixed(4)}, ${this.tokens} tokens over ${this.runs} runs)`,
      );
    }
  }

  summary(): string {
    const cap = this.ceilingUsd > 0 ? ` / $${this.ceilingUsd.toFixed(2)} cap` : ' (no cap)';
    return `$${this.spentUsd.toFixed(4)}${cap} · ${this.tokens} tokens · ${this.runs} LLM runs`;
  }
}

export class BudgetExceededError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BudgetExceededError';
  }
}
