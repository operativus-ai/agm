// Offline unit tests for the error-taxonomy classifier — pins the mapping that
// tests and UIs branch on (auth vs provider-key vs validation vs server).

import { describe, expect, it } from 'vitest';
import { classifyError, problemDetail } from '@agm/sdk';

describe('classifyError', () => {
  it('401 → auth (stale JWT / null-org principal)', () => {
    expect(classifyError(401, 'Unauthorized')).toBe('auth');
  });

  it('400 with "No API key configured" → provider-key (environment gap, not a bug)', () => {
    expect(classifyError(400, "No API key configured for provider 'OPENAI'")).toBe('provider-key');
    expect(classifyError(400, 'no api key configured for provider GOOGLE')).toBe('provider-key');
  });

  it('other 4xx → validation (concurrency cap, non-vision media, depth ≥ 5, …)', () => {
    expect(classifyError(400, 'Concurrency limit reached')).toBe('validation');
    expect(classifyError(404, 'Agent not found')).toBe('validation');
    expect(classifyError(409, 'conflict')).toBe('validation');
  });

  it('5xx → server', () => {
    expect(classifyError(500, 'boom')).toBe('server');
    expect(classifyError(503, 'unavailable')).toBe('server');
  });
});

describe('problemDetail', () => {
  it('prefers RFC 7807 detail', () => {
    expect(problemDetail({ detail: 'the detail', message: 'msg' }, 'fb')).toBe('the detail');
  });
  it('falls back through message and error to the fallback', () => {
    expect(problemDetail({ message: 'msg' }, 'fb')).toBe('msg');
    expect(problemDetail({ error: 'err' }, 'fb')).toBe('err');
    expect(problemDetail(undefined, 'fb')).toBe('fb');
    expect(problemDetail({}, 'fb')).toBe('fb');
  });
});
