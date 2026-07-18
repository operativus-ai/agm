// Mode A — Knowledge / RAG. Create a KB, upload a doc, poll ingestion, search.

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { RagDocument } from '@agm/sdk';
import { useClient } from '../lib/session';

function docText(d: RagDocument): string {
  return d.text ?? d.content ?? d.formattedContent ?? '';
}

export function KnowledgePanel() {
  const client = useClient();
  const kbs = useQuery({ queryKey: ['kbs'], queryFn: () => client.http.get<Array<{ id: string; name: string }>>('/v1/knowledge-bases') });

  const [kbName, setKbName] = useState('console-kb');
  const [kbId, setKbId] = useState<string>('');
  const [docBody, setDocBody] = useState('The classified project codename is ZEPHYR-DEMO.');
  const [query, setQuery] = useState('classified project codename');
  const [status, setStatus] = useState<string>('');
  const [hits, setHits] = useState<RagDocument[] | null>(null);
  const [busy, setBusy] = useState(false);

  async function createKb() {
    setBusy(true); setStatus('creating KB…');
    try {
      const kb = await client.createKnowledgeBase(kbName, 'console fixture');
      setKbId(kb.id); setStatus(`KB created: ${kb.id}`);
      kbs.refetch();
    } catch (e) { setStatus(`error: ${(e as Error).message}`); } finally { setBusy(false); }
  }

  async function upload() {
    if (!kbId) { setStatus('create/select a KB first'); return; }
    setBusy(true); setStatus('uploading…');
    try {
      const file = new File([docBody], 'console-doc.txt', { type: 'text/plain' });
      await client.uploadDocs(kbId, [file]);
      setStatus('uploaded — polling ingestion…');
      for (let i = 0; i < 20; i++) {
        const page = await client.listKnowledge(kbId);
        const items = page.content ?? [];
        if (items.length && items.every((c) => c.status === 'COMPLETED' || c.status === 'FAILED')) {
          setStatus(`ingestion: ${items.map((c) => c.status).join(', ')}`);
          return;
        }
        await new Promise((r) => setTimeout(r, 1500));
      }
      setStatus('ingestion still processing after 30s');
    } catch (e) { setStatus(`error: ${(e as Error).message}`); } finally { setBusy(false); }
  }

  async function search() {
    setBusy(true); setStatus('searching…');
    try {
      setHits(await client.searchKnowledge(query));
      setStatus('search done');
    } catch (e) { setStatus(`error: ${(e as Error).message}`); } finally { setBusy(false); }
  }

  return (
    <div className="panel">
      <h2>Knowledge / RAG</h2>
      <div className="grid2">
        <div className="card">
          <h3>1. Knowledge base</h3>
          <label>Name<input value={kbName} onChange={(e) => setKbName(e.target.value)} /></label>
          <button onClick={createKb} disabled={busy}>Create KB</button>
          <label>Or select existing
            <select value={kbId} onChange={(e) => setKbId(e.target.value)}>
              <option value="">—</option>
              {kbs.data?.map((k) => <option key={k.id} value={k.id}>{k.name}</option>)}
            </select>
          </label>
          <p className="muted mono">{kbId ? `active KB ${kbId.slice(0, 8)}` : 'no KB selected'}</p>
        </div>
        <div className="card">
          <h3>2. Upload a doc</h3>
          <textarea value={docBody} onChange={(e) => setDocBody(e.target.value)} rows={4} />
          <button onClick={upload} disabled={busy || !kbId}>Upload + ingest</button>
        </div>
      </div>
      <div className="card">
        <h3>3. Search</h3>
        <div className="row">
          <input value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1 }} />
          <button onClick={search} disabled={busy}>Search</button>
        </div>
        {hits && (
          <div className="results">
            {hits.length === 0 && <p className="muted">no hits</p>}
            {hits.map((d, i) => (
              <div key={i} className="hit">
                <span className="chip">{typeof d.score === 'number' ? d.score.toFixed(3) : (d.metadata?.distance as number)?.toFixed?.(3) ?? '—'}</span>
                <span>{docText(d).slice(0, 200)}</span>
              </div>
            ))}
          </div>
        )}
      </div>
      {status && <p className="muted status">{status}</p>}
    </div>
  );
}
