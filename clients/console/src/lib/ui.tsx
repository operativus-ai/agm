// Small shared UI helpers to keep panels terse.

import type { ReactNode } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useClient } from './session';
import type { AgmClient } from '@agm/sdk';

/** Typed GET-backed query keyed by path. */
export function useGet<T>(key: unknown[], fn: (c: AgmClient) => Promise<T>, enabled = true): UseQueryResult<T> {
  const client = useClient();
  return useQuery({ queryKey: key, queryFn: () => fn(client), enabled });
}

/** Normalize a Spring response that may be a bare array or a Page<T>. */
export function rows<T>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[];
  const page = data as { content?: T[] } | undefined;
  return page?.content ?? [];
}

export function QueryState({ q }: { q: UseQueryResult<unknown> }) {
  if (q.isLoading) return <p className="muted">loading…</p>;
  if (q.error) return <div className="error">{(q.error as Error).message}</div>;
  return null;
}

export function Panel({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children: ReactNode }) {
  return (
    <div className="panel">
      <h2>{title} {subtitle && <span className="muted subtitle">{subtitle}</span>}</h2>
      {children}
    </div>
  );
}

export function Json({ value }: { value: unknown }) {
  return <pre className="output small json">{JSON.stringify(value, null, 2)}</pre>;
}
