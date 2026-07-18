import { afterEach, describe, expect, it, vi } from 'vitest';
import { A2aApi, isTerminalA2aStatus, type A2aTaskStatusEvent } from './a2aApi';
import { ApiClient } from '../../../shared/api/client';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('A2aApi.submitTaskUrl', () => {
  it('returns the absolute /api-prefixed task path', () => {
    expect(A2aApi.submitTaskUrl()).toBe('/api/v1/a2a/tasks');
  });
});

describe('A2aApi.streamTask', () => {
  const captureStreamArgs = () => {
    const ctrl = new AbortController();
    const calls: Array<{ endpoint: string; options: Parameters<typeof ApiClient.stream>[1] }> = [];
    vi.spyOn(ApiClient, 'stream').mockImplementation((endpoint, options) => {
      calls.push({ endpoint, options });
      return ctrl;
    });
    return { ctrl, calls };
  };

  it('forwards request body as POST against the SSE endpoint', () => {
    const { ctrl, calls } = captureStreamArgs();

    const result = A2aApi.streamTask(
      { targetAgentId: 'agent-7', input: 'hi' },
      { onStatus: () => {} }
    );

    expect(result).toBe(ctrl);
    expect(calls).toHaveLength(1);
    expect(calls[0].endpoint).toBe('/v1/a2a/tasks');
    expect(calls[0].options.method).toBe('POST');
    expect(calls[0].options.body).toEqual({ targetAgentId: 'agent-7', input: 'hi' });
  });

  it('parses a2a-task-status events into typed payloads', () => {
    const { calls } = captureStreamArgs();
    const onStatus = vi.fn();

    A2aApi.streamTask({ targetAgentId: 'a', input: 'b' }, { onStatus });

    const event: A2aTaskStatusEvent = {
      taskId: 't1',
      status: 'WORKING',
      runId: 'run-1',
      message: 'started',
      errorDetail: null,
      timestamp: '2026-04-27T00:00:00Z',
    };
    calls[0].options.onMessage({ event: 'a2a-task-status', data: JSON.stringify(event) });

    expect(onStatus).toHaveBeenCalledTimes(1);
    expect(onStatus).toHaveBeenCalledWith(event);
  });

  it('skips non-status SSE events (e.g. heartbeats)', () => {
    const { calls } = captureStreamArgs();
    const onStatus = vi.fn();

    A2aApi.streamTask({ targetAgentId: 'a', input: 'b' }, { onStatus });
    calls[0].options.onMessage({ event: 'heartbeat', data: '{}' });

    expect(onStatus).not.toHaveBeenCalled();
  });

  it('routes JSON parse failures to onError instead of throwing', () => {
    const { calls } = captureStreamArgs();
    const onStatus = vi.fn();
    const onError = vi.fn();

    A2aApi.streamTask({ targetAgentId: 'a', input: 'b' }, { onStatus, onError });
    calls[0].options.onMessage({ event: 'a2a-task-status', data: 'not-json' });

    expect(onStatus).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledTimes(1);
  });
});

describe('isTerminalA2aStatus', () => {
  it.each(['COMPLETED', 'FAILED', 'CANCELLED'] as const)('treats %s as terminal', (status) => {
    expect(isTerminalA2aStatus(status)).toBe(true);
  });

  it.each(['SUBMITTED', 'WORKING', 'PAUSED', 'BUDGET_HALT'] as const)(
    'treats %s as non-terminal',
    (status) => {
      expect(isTerminalA2aStatus(status)).toBe(false);
    }
  );
});
