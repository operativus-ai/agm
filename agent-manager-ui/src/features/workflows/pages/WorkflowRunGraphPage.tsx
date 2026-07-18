import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, useReactFlow,
  useNodesState, useEdgesState,
} from '@xyflow/react';
import type { Node, Edge, NodeTypes } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { LuArrowLeft, LuRefreshCw, LuPencil } from 'react-icons/lu';

import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import { AgentsApi } from '../../agents/api/agents-api';
import { workflowRunsApi, type WorkflowNodeRun, type WorkflowChildNodeRuns } from '../api/workflowRunsApi';
import type { WorkflowEdge } from '../../../shared/types/orchestration';
import type { AgentConfig } from '../../../shared/types/api';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { StepNode, type NodeRunStatus } from '../editor/WorkflowNodes';
import { buildGraph } from '../editor/buildGraph';
import { layoutGraph } from '../editor/layoutGraph';
import { STORAGE_KEYS } from '../../../shared/constants/storage-keys';

// DAG node WS event type → node status (events broadcast as Node{Started,Completed,Failed,Paused}).
const WS_EVENT_STATUS: Record<string, NodeRunStatus> = {
  NodeStarted: 'running',
  NodeCompleted: 'ok',
  NodeFailed: 'failed',
  NodePaused: 'paused',
};

// Module-scope so React Flow doesn't see a new nodeTypes object every render.
const nodeTypes: NodeTypes = { step: StepNode };

const POLL_MS = 1500;
const MAX_POLLS = 60; // ~90s safety cap

function statusOf(run: WorkflowNodeRun | undefined): NodeRunStatus {
  if (!run) return 'pending';
  if (run.paused) return 'paused';
  return run.success ? 'ok' : 'failed';
}

function RunGraph() {
  const { id = '', runId = '' } = useParams<{ id: string; runId: string }>();
  const navigate = useNavigate();
  const { fitView } = useReactFlow();

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, , onEdgesChange] = useEdgesState<Edge>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [polling, setPolling] = useState(false);
  const [live, setLive] = useState(false);
  const [counts, setCounts] = useState({ done: 0, total: 0 });
  // Nested sub-workflow traces (per WORKFLOW-node invocation) + node titles for their headers.
  const [childTraces, setChildTraces] = useState<WorkflowChildNodeRuns[]>([]);
  const [nodeTitles, setNodeTitles] = useState<Map<string, string>>(new Map());

  // Poll bookkeeping (refs so the interval closure stays stable).
  const prevCount = useRef(-1);
  const stable = useRef(0);
  const pollsRef = useRef(0);
  const wsRef = useRef<WebSocket | null>(null);

  // Apply node-run statuses onto the existing (laid-out) nodes. Only override when node-runs has a
  // row (a terminal node); otherwise keep the current status so a live WS "running" isn't clobbered
  // back to "pending" by a poll that races ahead of the persisted row.
  const applyStatuses = useCallback((runs: WorkflowNodeRun[]) => {
    const byNode = new Map(runs.map((r) => [r.nodeId, r]));
    setNodes((prev) => prev.map((n) => ({
      ...n,
      data: {
        ...n.data,
        status: byNode.has(n.id) ? statusOf(byNode.get(n.id)) : (n.data as { status?: NodeRunStatus }).status,
      },
    })));
    setCounts((c) => ({ done: byNode.size, total: c.total }));
    return byNode.size;
  }, [setNodes]);

  // Live WS push: flip a node the instant the scheduler signals it (vs the poll's ~1.5s lag).
  const setNodeStatus = useCallback((nodeId: string, status: NodeRunStatus) => {
    setNodes((prev) => prev.map((n) => (n.id === nodeId ? { ...n, data: { ...n.data, status } } : n)));
  }, [setNodes]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [wf, agentList, persistedEdges, runs, children] = await Promise.all([
        orchestrationApi.getWorkflow(id),
        AgentsApi.getAgents().catch(() => [] as AgentConfig[]),
        orchestrationApi.getWorkflowEdges(id).catch(() => [] as WorkflowEdge[]),
        workflowRunsApi.nodeRuns(runId).catch(() => [] as WorkflowNodeRun[]),
        workflowRunsApi.childNodeRuns(runId).catch(() => [] as WorkflowChildNodeRuns[]),
      ]);
      const names = new Map(agentList.map((a) => [a.agentId, a.name]));
      const graph = buildGraph(wf.steps ?? [], names, persistedEdges);
      const laid = await layoutGraph(graph.nodes, graph.edges);
      const byNode = new Map(runs.map((r) => [r.nodeId, r]));
      setNodes(laid.nodes.map((n) => ({ ...n, data: { ...n.data, status: statusOf(byNode.get(n.id)) } })));
      setChildTraces(children);
      setNodeTitles(new Map(laid.nodes.map((n) => [n.id, String((n.data as { title?: string }).title ?? n.id)])));
      setCounts({ done: byNode.size, total: laid.nodes.length });
      setTimeout(() => fitView({ padding: 0.2, duration: 300 }), 0);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load the run graph.');
    } finally {
      setLoading(false);
    }
  }, [id, runId, setNodes, fitView]);

  useEffect(() => { void load(); }, [load]);

  // Poll node-runs until the run settles (count stable, or a paused/failed node, or the cap).
  useEffect(() => {
    if (loading) return;
    prevCount.current = -1;
    stable.current = 0;
    pollsRef.current = 0;
    setPolling(true);
    const timer = setInterval(async () => {
      pollsRef.current += 1;
      let runs: WorkflowNodeRun[] = [];
      try {
        runs = await workflowRunsApi.nodeRuns(runId);
      } catch {
        // transient; keep trying until the cap
      }
      const count = applyStatuses(runs);
      const settled = runs.some((r) => r.paused || !r.success);
      stable.current = count === prevCount.current ? stable.current + 1 : 0;
      prevCount.current = count;
      if (settled || stable.current >= 2 || pollsRef.current >= MAX_POLLS) {
        clearInterval(timer);
        setPolling(false);
        // Sub-workflow traces accumulate while the run executes — refresh once it settles.
        workflowRunsApi.childNodeRuns(runId).then(setChildTraces).catch(() => { /* keep last */ });
      }
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [loading, runId, applyStatuses]);

  // Live push: subscribe to the workflow WS and flip nodes the instant the scheduler signals them.
  // The handler broadcasts to all sessions, so we filter by event type + payload.runId. The poll
  // above remains the fallback (missed events / already-finished runs).
  useEffect(() => {
    if (!id || !runId) return;
    const token = localStorage.getItem(STORAGE_KEYS.AUTH_TOKEN);
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    let ws: WebSocket;
    try {
      ws = new WebSocket(`${proto}//${window.location.host}/workflows/ws?workflowId=${id}&token=${token}`);
    } catch {
      return; // WS unavailable → polling already covers it
    }
    wsRef.current = ws;
    ws.onopen = () => setLive(true);
    ws.onclose = () => setLive(false);
    ws.onerror = () => setLive(false);
    ws.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data) as { event?: string; payload?: { runId?: string; nodeId?: string } };
        const status = msg.event ? WS_EVENT_STATUS[msg.event] : undefined;
        if (status && msg.payload?.runId === runId && msg.payload.nodeId) {
          setNodeStatus(msg.payload.nodeId, status);
        }
      } catch { /* ignore non-JSON / unrelated frames */ }
    };
    return () => { ws.close(); wsRef.current = null; };
  }, [id, runId, setNodeStatus]);

  const legend = useMemo(() => ([
    { label: 'running', cls: 'bg-info' },
    { label: 'completed', cls: 'bg-success-green' },
    { label: 'failed', cls: 'bg-error-red' },
    { label: 'paused', cls: 'bg-warning-amber' },
    { label: 'pending', cls: 'bg-theme-muted/40' },
  ]), []);

  return (
    <PageContainer>
      <div className="flex items-center justify-between gap-3 mb-4">
        <div className="flex items-center gap-3 min-w-0">
          <Button variant="ghost" size="sm" onClick={() => navigate(`/workflows/${id}/runs`)}>
            <LuArrowLeft size={16} /> Runs
          </Button>
          <h1 className="truncate text-lg font-semibold text-theme-foreground">
            Run <span className="font-mono text-theme-muted">{runId.slice(0, 8)}…</span>
            <span className="text-theme-muted"> · graph</span>
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <Link to={`/workflows/${id}/graph`} className="btn btn-sm btn-ghost"><LuPencil size={14} /> Edit graph</Link>
          <Button variant="secondary" size="sm" onClick={() => void load()}>
            <LuRefreshCw size={14} /> Refresh
          </Button>
        </div>
      </div>

      {error && <Alert severity="error" className="mb-3">{error}</Alert>}

      <div className="grid grid-cols-[1fr_220px] gap-4" style={{ height: 'calc(100vh - 220px)', minHeight: 480 }}>
        <div className="rounded-xl border border-obsidian-stroke bg-obsidian-base overflow-hidden">
          {loading ? (
            <div className="flex h-full items-center justify-center text-theme-muted">Loading run graph…</div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable={false}
              fitView
              proOptions={{ hideAttribution: true }}
            >
              <Background />
              <Controls showInteractive={false} />
            </ReactFlow>
          )}
        </div>

        <div className="flex flex-col gap-4">
          <div className="rounded-xl border border-obsidian-stroke bg-obsidian-elevated p-4 space-y-3">
            <div className="flex items-center justify-between">
              <div className="text-sm font-semibold text-theme-foreground">Progress</div>
              <div className="flex items-center gap-2">
                {live && (
                  <span className="inline-flex items-center gap-1 text-[10px] text-success-green" title="Live WebSocket connected">
                    <span className="inline-block w-1.5 h-1.5 rounded-full bg-success-green animate-pulse" /> live
                  </span>
                )}
                {polling && <span className="loading loading-spinner loading-xs text-info" title="Polling…" />}
              </div>
            </div>
            <div className="text-2xl font-semibold text-theme-foreground">
              {counts.done}<span className="text-theme-muted text-base"> / {counts.total}</span>
            </div>
            <div className="text-[11px] text-theme-muted">nodes executed</div>
          </div>

          <div className="rounded-xl border border-obsidian-stroke bg-obsidian-elevated p-4 space-y-2">
            <div className="text-sm font-semibold text-theme-foreground">Legend</div>
            {legend.map((l) => (
              <div key={l.label} className="flex items-center gap-2 text-xs text-theme-muted">
                <span className={`inline-block w-3 h-3 rounded-sm ${l.cls}`} /> {l.label}
              </div>
            ))}
          </div>

          {childTraces.length > 0 && (
            <div className="rounded-xl border border-obsidian-stroke bg-obsidian-elevated p-4 space-y-2 overflow-y-auto">
              <div className="text-sm font-semibold text-theme-foreground">Sub-workflow traces</div>
              {childTraces.map((group) => (
                <details key={group.childRunId} className="text-xs">
                  <summary className="cursor-pointer text-theme-foreground">
                    {nodeTitles.get(group.parentNodeId) ?? group.parentNodeId}
                    <span className="text-theme-muted"> · {group.nodeRuns.length} node{group.nodeRuns.length === 1 ? '' : 's'}</span>
                  </summary>
                  <ul className="mt-1 ml-3 space-y-1">
                    {group.nodeRuns.map((r) => (
                      <li key={r.id} className="flex items-center gap-1.5 text-theme-muted">
                        <span
                          className={`inline-block w-2 h-2 rounded-full shrink-0 ${
                            r.paused ? 'bg-warning-amber' : r.success ? 'bg-success-green' : 'bg-error-red'
                          }`}
                        />
                        <span className="truncate" title={r.content ?? undefined}>
                          {r.nodeName || r.nodeId} <span className="opacity-60">({r.kind})</span>
                        </span>
                      </li>
                    ))}
                  </ul>
                </details>
              ))}
            </div>
          )}

          {counts.total > 0 && counts.done === 0 && !polling && (
            <div className="text-[11px] leading-relaxed text-theme-muted px-1">
              No per-node trace for this run — it ran on the flat <span className="font-mono">step_order</span> engine,
              or the DAG engine is disabled for this workflow.
            </div>
          )}
        </div>
      </div>
    </PageContainer>
  );
}

export default function WorkflowRunGraphPage() {
  return (
    <ReactFlowProvider>
      <RunGraph />
    </ReactFlowProvider>
  );
}
