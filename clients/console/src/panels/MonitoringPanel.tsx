// Monitoring & observability — platform stats, security events, health.

import { Panel, QueryState, useGet, Json } from '../lib/ui';

export function MonitoringPanel() {
  const stats = useGet(['mon-stats'], (c) => c.http.get<unknown>('/monitoring/stats'));
  const events = useGet(['mon-sec-events'], (c) => c.http.get<unknown>('/monitoring/security/events'));
  const health = useGet(['health'], async (c) => ({ status: await c.healthStatus() }));

  return (
    <Panel title="Monitoring & observability">
      <div className="grid2">
        <div className="card">
          <h3>Platform stats</h3>
          <QueryState q={stats} />
          <Json value={stats.data} />
        </div>
        <div className="card">
          <h3>Liveness</h3>
          <Json value={health.data} />
          <p className="muted">GET /api/v1/health — 200 in prod; may 401 under the dev profile.</p>
          <h3>Security events</h3>
          <QueryState q={events} />
          <Json value={events.data} />
        </div>
      </div>
    </Panel>
  );
}
