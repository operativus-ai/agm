import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { TeamMemorySettings } from './TeamMemorySettings';
import type { Team } from '../../../shared/types/orchestration';

type MemoryFields = Pick<Team, 'memoryEnabled' | 'addHistoryToMessages' | 'isolateMemory'>;

const renderWithTeam = (overrides: Partial<MemoryFields> = {}) => {
  const onChange = vi.fn();
  const team: MemoryFields = {
    memoryEnabled: false,
    addHistoryToMessages: true,
    isolateMemory: false,
    ...overrides,
  };
  render(<TeamMemorySettings team={team as Team} onChange={onChange} />);
  return { onChange };
};

const isolateToggle = () => screen.getByTestId('team-isolate-memory-toggle') as HTMLInputElement;

describe('TeamMemorySettings — Isolate Member Memory toggle (§9 MEM-2)', () => {
  it('renders the labelled toggle and its help text', () => {
    renderWithTeam();
    expect(screen.getByText('Isolate Member Memory (§9 MEM-2)')).toBeInTheDocument();
    expect(
      screen.getByText(/each member keeps its own chat-memory bucket/i)
    ).toBeInTheDocument();
  });

  it('reflects isolateMemory=false (default) as unchecked', () => {
    renderWithTeam({ isolateMemory: false });
    expect(isolateToggle().checked).toBe(false);
  });

  it('treats undefined isolateMemory as unchecked (preserve default-OFF semantics)', () => {
    renderWithTeam({ isolateMemory: undefined });
    expect(isolateToggle().checked).toBe(false);
  });

  it('reflects isolateMemory=true as checked', () => {
    renderWithTeam({ isolateMemory: true });
    expect(isolateToggle().checked).toBe(true);
  });

  it('emits onChange({ isolateMemory: true }) when flipped on', () => {
    const { onChange } = renderWithTeam({ isolateMemory: false });
    fireEvent.click(isolateToggle());
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith({ isolateMemory: true });
  });

  it('emits onChange({ isolateMemory: false }) when flipped off', () => {
    const { onChange } = renderWithTeam({ isolateMemory: true });
    fireEvent.click(isolateToggle());
    expect(onChange).toHaveBeenCalledWith({ isolateMemory: false });
  });

  it('does not emit changes for the other two toggles when isolate is flipped', () => {
    const { onChange } = renderWithTeam();
    fireEvent.click(isolateToggle());
    const lastCall = onChange.mock.calls.at(-1)?.[0] ?? {};
    expect('memoryEnabled' in lastCall).toBe(false);
    expect('addHistoryToMessages' in lastCall).toBe(false);
  });
});

describe('TeamMemorySettings — sibling toggles still wire correctly', () => {
  it('Semantic Memory Graph emits memoryEnabled changes', () => {
    const { onChange } = renderWithTeam({ memoryEnabled: false });
    fireEvent.click(screen.getByTestId('team-memory-enabled-toggle'));
    expect(onChange).toHaveBeenCalledWith({ memoryEnabled: true });
  });

  it('Include Conversational History defaults to checked when undefined', () => {
    renderWithTeam({ addHistoryToMessages: undefined });
    expect((screen.getByTestId('team-add-history-toggle') as HTMLInputElement).checked).toBe(true);
  });

  it('Include Conversational History emits flips', () => {
    const { onChange } = renderWithTeam({ addHistoryToMessages: true });
    fireEvent.click(screen.getByTestId('team-add-history-toggle'));
    expect(onChange).toHaveBeenCalledWith({ addHistoryToMessages: false });
  });
});
