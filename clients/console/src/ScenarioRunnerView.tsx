// Mode B — Scenario Runner. Runs the @agm/sdk scenario registry in-browser
// against the logged-in identity; renders live PASS/FAIL/WARN/SKIP + evidence.

import { useMemo, useState } from 'react';
import type { ScenarioReport, Tier } from '@agm/sdk';
import { useSession } from './lib/session';
import { runScenariosInBrowser, scenarioCatalog } from './lib/browserRun';

export function ScenarioRunnerView() {
  const { session } = useSession();
  const catalog = useMemo(scenarioCatalog, []);
  const domains = useMemo(() => [...new Set(catalog.map((s) => s.domain))].sort(), [catalog]);

  const [maxTier, setMaxTier] = useState<Tier | ''>('');
  const [domain, setDomain] = useState('');
  const [budgetUsd, setBudgetUsd] = useState(0.5);
  const [running, setRunning] = useState(false);
  const [reports, setReports] = useState<ScenarioReport[] | null>(null);
  const [lastRunId, setLastRunId] = useState('');

  const shown = catalog.filter((s) => (!domain || s.domain === domain) && (!maxTier || rank(s.tier) <= rank(maxTier)));

  async function run() {
    if (!session) return;
    setRunning(true);
    setReports(null);
    try {
      const { id, reports } = await runScenariosInBrowser(session.client, session.auth, {
        maxTier: maxTier || undefined,
        domain: domain || undefined,
        budgetUsd,
      });
      setLastRunId(id);
      setReports(reports);
    } finally {
      setRunning(false);
    }
  }

  function download() {
    if (!reports) return;
    const blob = new Blob([JSON.stringify({ runId: lastRunId, scenarios: reports }, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `report-${lastRunId}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  const counts = reports ? tally(reports) : null;

  return (
    <div className="panel">
      <h2>Scenario Runner <span className="muted">(Mode B — the harness, in-browser)</span></h2>
      <div className="row controls">
        <label>Max tier
          <select value={maxTier} onChange={(e) => setMaxTier(e.target.value as Tier | '')}>
            <option value="">all</option>
            <option value="T0">T0 (offline)</option>
            <option value="T1">T1 (live, no-LLM)</option>
            <option value="T2">T2 (live + LLM)</option>
          </select>
        </label>
        <label>Domain
          <select value={domain} onChange={(e) => setDomain(e.target.value)}>
            <option value="">all</option>
            {domains.map((d) => <option key={d} value={d}>{d}</option>)}
          </select>
        </label>
        <label>Budget $
          <input type="number" step="0.1" min="0" value={budgetUsd} onChange={(e) => setBudgetUsd(Number(e.target.value))} style={{ width: 70 }} />
        </label>
        <button onClick={run} disabled={running}>{running ? 'Running…' : `Run ${shown.length} scenarios`}</button>
        {reports && <button className="link" onClick={download}>Download JSON</button>}
      </div>

      {counts && (
        <p className="summary">
          <span className="ok">{counts.PASS} pass</span> · <span className="bad">{counts.FAIL} fail</span> ·{' '}
          <span className="warnc">{counts.WARN} warn</span> · <span className="muted">{counts.SKIP} skip</span>
        </p>
      )}

      <table className="data scenarios">
        <thead><tr><th></th><th>id</th><th>tier</th><th>title</th><th>result</th></tr></thead>
        <tbody>
          {(reports ?? shown.map((s) => ({ ...s, outcome: undefined as never, note: undefined, durationMs: 0 }))).map((r) => {
            const rep = reports?.find((x) => x.id === r.id);
            return (
              <tr key={r.id}>
                <td>{icon(rep?.outcome)}</td>
                <td className="mono">{r.id}</td>
                <td><span className="chip">{r.tier}</span></td>
                <td>{r.title}</td>
                <td className={cls(rep?.outcome)}>
                  {rep ? `${rep.outcome}${rep.note ? ` — ${rep.note}` : ''}${rep.durationMs ? ` (${rep.durationMs}ms)` : ''}` : (running ? '…' : '')}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function rank(t: Tier): number { return { T0: 0, T1: 1, T2: 2 }[t]; }
function tally(rs: ScenarioReport[]) {
  const c = { PASS: 0, FAIL: 0, WARN: 0, SKIP: 0 };
  for (const r of rs) c[r.outcome]++;
  return c;
}
function icon(o?: string) { return o ? ({ PASS: '✅', FAIL: '❌', WARN: '⚠️', SKIP: '⏭️' }[o] ?? '') : ''; }
function cls(o?: string) { return o === 'PASS' ? 'ok' : o === 'FAIL' ? 'bad' : o === 'WARN' ? 'warnc' : 'muted'; }
