import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { UsageSummaryBadge } from './UsageSummary';
import { formatTokenCount, formatUsd } from '../utils/usage-format';

describe('formatTokenCount', () => {
  it('thousands-separates', () => {
    expect(formatTokenCount(1500)).toBe('1,500');
    expect(formatTokenCount(42)).toBe('42');
  });
});

describe('formatUsd', () => {
  it('uses 4 decimals for sub-cent costs so they are not rounded to $0.00', () => {
    expect(formatUsd(0.0034)).toBe('$0.0034');
  });
  it('uses 2 decimals for cent-and-above costs', () => {
    expect(formatUsd(7.5)).toBe('$7.50');
  });
});

describe('UsageSummaryBadge', () => {
  it('renders total tokens, cost, and model', () => {
    render(<UsageSummaryBadge usage={{ inputTokens: 1000, outputTokens: 500, totalTokens: 1500, costUsd: 7.5, model: 'gpt-4o', llmCalls: 2 }} />);
    const badge = screen.getByTestId('usage-summary');
    expect(badge.textContent).toContain('1,500 tokens');
    expect(badge.textContent).toContain('$7.50');
    expect(badge.textContent).toContain('gpt-4o');
  });

  it('derives total from input+output+reasoning when totalTokens is absent', () => {
    render(<UsageSummaryBadge usage={{ inputTokens: 100, outputTokens: 50, reasoningTokens: 10 }} />);
    expect(screen.getByTestId('usage-summary').textContent).toContain('160 tokens');
  });

  it('omits cost when costUsd is absent or zero', () => {
    render(<UsageSummaryBadge usage={{ totalTokens: 300, model: 'gemini-2.5-pro' }} />);
    const badge = screen.getByTestId('usage-summary');
    expect(badge.textContent).toContain('300 tokens');
    expect(badge.textContent).not.toContain('$');
  });

  it('renders nothing when usage is undefined', () => {
    const { container } = render(<UsageSummaryBadge usage={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when there are zero tokens (provider reported no usage)', () => {
    const { container } = render(<UsageSummaryBadge usage={{ inputTokens: 0, outputTokens: 0, llmCalls: 1 }} />);
    expect(container).toBeEmptyDOMElement();
  });
});
