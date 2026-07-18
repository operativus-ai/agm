import { useCallback, useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  ReactFlow, ReactFlowProvider, Background, Controls, useReactFlow,
  useNodesState, useEdgesState,
} from '@xyflow/react';
import type { Node, Edge, NodeTypes, Connection } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { LuArrowLeft, LuPlus, LuTrash2, LuWand, LuHistory, LuPencil, LuSave, LuPlay } from 'react-icons/lu';

import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import { AgentsApi } from '../../agents/api/agents-api';
import type { Workflow, WorkflowStep, WorkflowStepType, WorkflowEdge, RouteSelectorType, WorkflowValidationResult } from '../../../shared/types/orchestration';
import type { AgentConfig } from '../../../shared/types/api';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { StepNode, type StepNodeData } from '../editor/WorkflowNodes';
import { GraphOverview } from '../editor/GraphOverview';
import { buildGraph, type GraphEdgeData } from '../editor/buildGraph';
import { layoutGraph } from '../editor/layoutGraph';

const ADDABLE_KINDS: WorkflowStepType[] =
  ['AGENT', 'CONDITION', 'PARALLEL', 'JOIN', 'LOOP', 'WEBHOOK', 'ROUTER', 'FUNCTION', 'WORKFLOW'];
const ROUTE_SELECTORS: RouteSelectorType[] = ['RULE', 'LLM', 'HITL'];

// Node kinds whose outgoing edges carry a port label the DAG scheduler routes on.
const BRANCHING_KINDS = new Set(['CONDITION', 'ROUTER', 'LOOP']);

// Fixed port labels per kind (ROUTER uses a free-text choice key instead). A LOOP's 'back' edge
// (body → loop) is the one sanctioned cycle — the DAG validator now exempts the 'back' port.
function fixedPorts(kind: string): string[] {
  if (kind === 'CONDITION') return ['true', 'false'];
  if (kind === 'LOOP') return ['loop', 'exit', 'back'];
  return [];
}

// Module-scope so React Flow doesn't see a new nodeTypes object every render.
const nodeTypes: NodeTypes = { step: StepNode };

function GraphEditor() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { fitView, getNodes } = useReactFlow();

  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [agents, setAgents] = useState<AgentConfig[]>([]);
  // Other workflows, for the WORKFLOW (sub-workflow) node's child picker.
  const [workflowOptions, setWorkflowOptions] = useState<Workflow[]>([]);
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [edgeRouterKey, setEdgeRouterKey] = useState('');
  const [editValue, setEditValue] = useState('');
  // Inspector ROUTER sub-fields (ROUTER config lives in routerConfig, not agentId).
  const [editRouterSelector, setEditRouterSelector] = useState<RouteSelectorType>('RULE');
  const [editRouterExpr, setEditRouterExpr] = useState('');
  const [editRouterChoices, setEditRouterChoices] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validation, setValidation] = useState<WorkflowValidationResult | null>(null);

  // Run-workflow dialog
  const [runOpen, setRunOpen] = useState(false);
  const [runInput, setRunInput] = useState('');
  const [runSessionId, setRunSessionId] = useState('');
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  // Add-step form
  const [addKind, setAddKind] = useState<WorkflowStepType>('AGENT');
  const [addAgentId, setAddAgentId] = useState('');
  const [addExpr, setAddExpr] = useState('');
  const [addWorkflowId, setAddWorkflowId] = useState('');
  // ROUTER add-form sub-fields
  const [routerSelector, setRouterSelector] = useState<RouteSelectorType>('RULE');
  const [routerExpr, setRouterExpr] = useState('');
  const [routerChoices, setRouterChoices] = useState('');

  // Pending connection awaiting a port-label choice (branching source nodes).
  const [pendingConn, setPendingConn] = useState<Connection | null>(null);
  const [pendingKind, setPendingKind] = useState<string>('AGENT');
  const [routerKey, setRouterKey] = useState('');

  const relayout = useCallback(async (
    steps: WorkflowStep[],
    names: Map<string, string>,
    persistedEdges: WorkflowEdge[],
    orphanStepIds: Set<string> = new Set(),
    savedPositions?: Map<string, { x: number; y: number }>,
  ) => {
    const graph = buildGraph(steps, names, persistedEdges, orphanStepIds);
    const laid = await layoutGraph(graph.nodes, graph.edges);
    // Saved manual positions win over ELK; nodes without one (e.g. just-added) keep the ELK spot.
    const nodes = savedPositions && savedPositions.size > 0
      ? laid.nodes.map((n) => {
          const p = savedPositions.get(n.id);
          return p ? { ...n, position: p } : n;
        })
      : laid.nodes;
    setNodes(nodes);
    setEdges(laid.edges);
    setTimeout(() => fitView({ padding: 0.2, duration: 300 }), 0);
  }, [setNodes, setEdges, fitView]);

  // Persist the full set of node positions after a drag (best-effort; layout is non-critical).
  const persistLayout = useCallback(async () => {
    const positions = getNodes().map((n) => ({ stepId: n.id, x: n.position.x, y: n.position.y }));
    try {
      await orchestrationApi.saveWorkflowLayout(id, { positions });
    } catch {
      // Layout is a convenience, not correctness — swallow so a transient failure doesn't disrupt editing.
    }
  }, [id, getNodes]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [wf, agentList, persistedEdges, report, layout, wfPage] = await Promise.all([
        orchestrationApi.getWorkflow(id),
        AgentsApi.getAgents().catch(() => [] as AgentConfig[]),
        orchestrationApi.getWorkflowEdges(id).catch(() => [] as WorkflowEdge[]),
        orchestrationApi.validateWorkflowGraph(id).catch(() => null as WorkflowValidationResult | null),
        orchestrationApi.getWorkflowLayout(id).catch(() => null),
        orchestrationApi.getWorkflows({ page: 0, size: 100 }).catch(() => null),
      ]);
      setWorkflow(wf);
      setAgents(agentList);
      // Sub-workflow picker: every other workflow (self-nesting is depth-guarded server-side but
      // pointless to offer).
      setWorkflowOptions((wfPage?.content ?? []).filter((w) => w.id !== id));
      setValidation(report);
      const orphans = new Set(report?.unreachableStepIds ?? []);
      const savedPositions = new Map((layout?.positions ?? []).map((p) => [p.stepId, { x: p.x, y: p.y }]));
      await relayout(wf.steps ?? [], new Map(agentList.map((a) => [a.agentId, a.name])), persistedEdges, orphans, savedPositions);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load workflow.');
    } finally {
      setLoading(false);
    }
  }, [id, relayout]);

  useEffect(() => { void load(); }, [load]);

  const nextStepOrder = useMemo(() => {
    const orders = (workflow?.steps ?? []).map((s) => s.stepOrder);
    return orders.length ? Math.max(...orders) + 1 : 1;
  }, [workflow]);

  const handleAdd = useCallback(async () => {
    if (!workflow) return;
    // PARALLEL is a structural fan-out gate on the DAG path — no agent required (JOIN likewise).
    if (addKind === 'AGENT' && !addAgentId) {
      setError('Select an agent for this step.');
      return;
    }
    if ((addKind === 'CONDITION' || addKind === 'WEBHOOK' || addKind === 'LOOP') && !addExpr.trim()) {
      setError('Provide the expression / URL / loop config for this step.');
      return;
    }
    if (addKind === 'FUNCTION' && !addExpr.trim()) {
      setError('Provide the registered function key for this step.');
      return;
    }
    if (addKind === 'WORKFLOW' && !addWorkflowId) {
      setError('Select the sub-workflow this step runs.');
      return;
    }
    const choiceKeys = routerChoices.split(',').map((c) => c.trim()).filter(Boolean);
    if (addKind === 'ROUTER') {
      if (routerSelector !== 'HITL' && !routerExpr.trim()) {
        setError('Provide the selector expression (JSONPath for RULE, prompt for LLM).');
        return;
      }
      if (choiceKeys.length === 0) {
        setError('Provide at least one comma-separated choice key for the router.');
        return;
      }
    }
    setBusy(true);
    setError(null);
    try {
      // agent_id is the per-kind config column: agent for AGENT (optional for PARALLEL), child
      // workflow id for WORKFLOW, function key / expression / URL / loop config for the rest;
      // ROUTER config lives in routerConfig and JOIN is config-free.
      const payload: Partial<WorkflowStep> = {
        workflowId: id,
        stepOrder: nextStepOrder,
        stepType: addKind,
        agentId: addKind === 'AGENT' || addKind === 'PARALLEL' ? (addAgentId || undefined)
          : addKind === 'WORKFLOW' ? addWorkflowId
          : addKind === 'ROUTER' || addKind === 'JOIN' ? undefined
          : addExpr.trim(),
        action: addKind,
      };
      if (addKind === 'ROUTER') {
        // choiceKey → target stepId; the DAG edges (drawn with these port labels) carry the actual
        // routing, so values mirror the keys until edges are wired.
        const choices: Record<string, string> = {};
        choiceKeys.forEach((k) => { choices[k] = k; });
        payload.routerConfig = {
          selectorType: routerSelector,
          selectorExpression: routerSelector === 'HITL' ? null : routerExpr.trim(),
          choices,
          defaultChoice: choiceKeys[0],
        };
      }
      await orchestrationApi.addWorkflowStep(id, payload);
      setAddAgentId('');
      setAddExpr('');
      setAddWorkflowId('');
      setRouterExpr('');
      setRouterChoices('');
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add step.');
    } finally {
      setBusy(false);
    }
  }, [workflow, addKind, addAgentId, addExpr, addWorkflowId, routerSelector, routerExpr, routerChoices, id, nextStepOrder, load]);

  // Single path for every deletion trigger: the inspector button, the Delete/Backspace key
  // (React Flow onNodesDelete), and multi-selection. Persists each step then reloads so the
  // canvas stays in sync with the server (and resyncs even on partial failure).
  const deleteSteps = useCallback(async (ids: string[]) => {
    const unique = [...new Set(ids)].filter(Boolean);
    if (unique.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      for (const stepId of unique) {
        await orchestrationApi.removeWorkflowStep(id, stepId);
      }
      setSelectedId((prev) => (prev && unique.includes(prev) ? null : prev));
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete step(s).');
      await load(); // resync canvas with server after a partial failure
    } finally {
      setBusy(false);
    }
  }, [id, load]);

  // Persist a new DAG edge (optionally port-labeled), then reload so the served graph (now
  // explicit-edge mode) replaces the derived step_order view. The backend rejects
  // self-loops / cross-workflow steps / duplicates / cycles with a 400 we surface.
  const persistEdge = useCallback(async (conn: Connection, condition: string | null) => {
    if (!conn.source || !conn.target) return;
    setBusy(true);
    setError(null);
    try {
      await orchestrationApi.addWorkflowEdge(id, {
        fromStepId: conn.source, toStepId: conn.target, condition: condition ?? undefined,
      });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add edge.');
    } finally {
      setBusy(false);
    }
  }, [id, load]);

  // Draw-to-connect: a branching source (CONDITION/ROUTER/LOOP) opens the port picker; any other
  // source persists an unconditional edge immediately.
  const handleConnect = useCallback((conn: Connection) => {
    if (!conn.source || !conn.target) return;
    if (conn.source === conn.target) {
      setError('An edge cannot connect a step to itself.');
      return;
    }
    const src = nodes.find((n) => n.id === conn.source);
    const kind = (src?.data as StepNodeData | undefined)?.kind ?? 'AGENT';
    if (BRANCHING_KINDS.has(kind)) {
      setPendingKind(kind);
      setRouterKey('');
      setPendingConn(conn);
    } else {
      void persistEdge(conn, null);
    }
  }, [nodes, persistEdge]);

  // Only persisted edges have a backend row to delete; derived (dashed) edges are
  // non-deletable, so a delete trigger on them just resyncs from the server.
  const handleEdgesDelete = useCallback(async (removed: Edge[]) => {
    const ids = removed
      .filter((e) => (e.data as GraphEdgeData | undefined)?.persisted)
      .map((e) => e.id);
    if (ids.length === 0) {
      await load();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      for (const edgeId of ids) {
        await orchestrationApi.removeWorkflowEdge(id, edgeId);
      }
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete edge(s).');
      await load();
    } finally {
      setBusy(false);
    }
  }, [id, load]);

  const selectedNode = nodes.find((n) => n.id === selectedId);
  const selectedData = selectedNode?.data as StepNodeData | undefined;
  const selectedStep = (workflow?.steps ?? []).find((s) => s.id === selectedId);
  const selectedIsAgent = selectedData?.kind === 'AGENT' || selectedData?.kind === 'PARALLEL';
  const selectedIsRouter = selectedData?.kind === 'ROUTER';

  // Edge selection (port relabeling). Only persisted edges have a backend row to PATCH; derived
  // (dashed, step_order) edges aren't relabelable.
  const selectedEdge = edges.find((e) => e.id === selectedEdgeId);
  const selectedEdgePersisted = (selectedEdge?.data as GraphEdgeData | undefined)?.persisted === true;
  const edgeSourceData = nodes.find((n) => n.id === selectedEdge?.source)?.data as StepNodeData | undefined;
  const edgeTargetData = nodes.find((n) => n.id === selectedEdge?.target)?.data as StepNodeData | undefined;
  const edgeSourceKind = edgeSourceData?.kind ?? 'AGENT';
  const edgeCurrentPort = typeof selectedEdge?.label === 'string' ? selectedEdge.label : null;

  // Relabel the selected edge's port (condition), then reload. The backend reverts + 400s on a
  // duplicate or a cycle (relabeling to/from the 'back' LOOP port shifts cycle adjacency).
  const relabelEdge = useCallback(async (condition: string | null) => {
    if (!selectedEdgeId) return;
    setBusy(true);
    setError(null);
    try {
      await orchestrationApi.updateWorkflowEdge(id, selectedEdgeId, { condition });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to relabel edge.');
    } finally {
      setBusy(false);
    }
  }, [id, selectedEdgeId, load]);
  const selectedRouterChoiceKeys = selectedStep?.routerConfig
    ? Object.keys(selectedStep.routerConfig.choices ?? {}).join(', ')
    : '';

  // Sync the inspector's editable values to the selected step (agent/expression, or router config).
  useEffect(() => {
    setEditValue(selectedStep?.agentId ?? '');
    const rc = selectedStep?.routerConfig;
    setEditRouterSelector(rc?.selectorType ?? 'RULE');
    setEditRouterExpr(rc?.selectorExpression ?? '');
    setEditRouterChoices(rc ? Object.keys(rc.choices ?? {}).join(', ') : '');
  }, [selectedId, selectedStep?.agentId, selectedStep?.routerConfig]);

  // Whether the inspector has unsaved edits (drives the Save button's disabled state).
  const inspectorDirty = selectedIsRouter
    ? editRouterSelector !== (selectedStep?.routerConfig?.selectorType ?? 'RULE')
      || editRouterExpr !== (selectedStep?.routerConfig?.selectorExpression ?? '')
      || editRouterChoices !== selectedRouterChoiceKeys
    : editValue !== (selectedStep?.agentId ?? '');

  // Save the inspector edit — updates the agent/expression or router config (action/kind stays
  // fixed server-side). ROUTER config lives in routerConfig; everything else in agentId.
  const handleUpdateStep = useCallback(async () => {
    if (!selectedId || !selectedStep) return;
    const payload: Partial<WorkflowStep> = { stepOrder: selectedStep.stepOrder };
    if (selectedIsRouter) {
      const keys = editRouterChoices.split(',').map((c) => c.trim()).filter(Boolean);
      if (editRouterSelector !== 'HITL' && !editRouterExpr.trim()) {
        setError('Provide the selector expression (JSONPath for RULE, prompt for LLM).');
        return;
      }
      if (keys.length === 0) {
        setError('Provide at least one comma-separated choice key for the router.');
        return;
      }
      // Preserve any existing choiceKey → target-stepId mapping; new keys mirror the key until
      // their outgoing DAG edge is drawn (same convention as the add-form).
      const prevChoices = selectedStep.routerConfig?.choices ?? {};
      const choices: Record<string, string> = {};
      keys.forEach((k) => { choices[k] = prevChoices[k] ?? k; });
      const prevDefault = selectedStep.routerConfig?.defaultChoice ?? '';
      payload.routerConfig = {
        selectorType: editRouterSelector,
        selectorExpression: editRouterSelector === 'HITL' ? null : editRouterExpr.trim(),
        choices,
        defaultChoice: keys.includes(prevDefault) ? prevDefault : keys[0],
      };
    } else {
      payload.agentId = editValue.trim() || undefined;
    }
    setBusy(true);
    setError(null);
    try {
      await orchestrationApi.updateWorkflowStep(id, selectedId, payload);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update step.');
    } finally {
      setBusy(false);
    }
  }, [id, selectedId, selectedStep, selectedIsRouter, editValue, editRouterSelector, editRouterExpr, editRouterChoices, load]);

  const hasSteps = (workflow?.steps?.length ?? 0) > 0;

  // Trigger a run from the editor, then jump to run history (the execute response carries a
  // jobId/sessionId, not a runId — same handoff as the Workflows list's Run action).
  const handleRun = useCallback(async () => {
    if (!runInput.trim()) {
      setRunError('Input is required.');
      return;
    }
    setRunning(true);
    setRunError(null);
    try {
      await orchestrationApi.runWorkflow(id, runInput.trim(), runSessionId.trim() || undefined);
      setRunOpen(false);
      navigate(`/workflows/${id}/runs`);
    } catch (err) {
      setRunError(err instanceof Error ? err.message : 'Workflow run submission failed.');
    } finally {
      setRunning(false);
    }
  }, [id, runInput, runSessionId, navigate]);

  return (
    <PageContainer>
      <div className="flex items-center justify-between gap-3 mb-4">
        <div className="flex items-center gap-3 min-w-0">
          <Button variant="ghost" size="sm" onClick={() => navigate('/workflows')}>
            <LuArrowLeft size={16} /> Workflows
          </Button>
          <div className="min-w-0">
            <h1 className="truncate text-lg font-semibold text-theme-foreground">
              {workflow?.name ?? 'Workflow'} <span className="text-theme-muted">· graph</span>
            </h1>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Link to={`/workflows/${id}`} className="btn btn-sm btn-ghost"><LuPencil size={14} /> Form editor</Link>
          <Link to={`/workflows/${id}/runs`} className="btn btn-sm btn-ghost"><LuHistory size={14} /> Runs</Link>
          <Button variant="secondary" size="sm" onClick={() => fitView({ padding: 0.2, duration: 300 })}>
            <LuWand size={14} /> Fit
          </Button>
          <Button
            variant="primary" size="sm" disabled={!hasSteps}
            title={hasSteps ? 'Run this workflow' : 'Add at least one step to run'}
            onClick={() => { setRunInput(''); setRunSessionId(''); setRunError(null); setRunOpen(true); }}
          >
            <LuPlay size={14} /> Run
          </Button>
        </div>
      </div>

      {error && <Alert severity="error" className="mb-3">{error}</Alert>}

      {validation && !validation.valid && (
        <Alert severity="warning" className="mb-3">
          {validation.hasCycle
            ? (validation.cycleMessage ?? 'The graph contains a cycle.')
            : `${validation.unreachableStepIds.length} step${validation.unreachableStepIds.length === 1 ? '' : 's'} unreachable from the start step (highlighted amber). Add an inbound edge to wire ${validation.unreachableStepIds.length === 1 ? 'it' : 'them'} into the flow.`}
        </Alert>
      )}

      <div className="grid grid-cols-[1fr_320px] gap-4" style={{ height: 'calc(100vh - 220px)', minHeight: 480 }}>
        {/* Canvas */}
        <div className="rounded-xl border border-obsidian-stroke bg-obsidian-base overflow-hidden">
          {loading ? (
            <div className="flex h-full items-center justify-center text-theme-muted">Loading graph…</div>
          ) : (
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onNodeClick={(_, node) => { setSelectedId(node.id); setSelectedEdgeId(null); }}
              onEdgeClick={(_, edge) => { setSelectedEdgeId(edge.id); setSelectedId(null); }}
              onPaneClick={() => { setSelectedId(null); setSelectedEdgeId(null); }}
              onConnect={(conn) => void handleConnect(conn)}
              onNodesDelete={(deleted) => void deleteSteps(deleted.map((n) => n.id))}
              onNodeDragStop={() => void persistLayout()}
              onEdgesDelete={(deleted) => void handleEdgesDelete(deleted)}
              deleteKeyCode={['Delete', 'Backspace']}
              multiSelectionKeyCode={['Meta', 'Shift']}
              fitView
              proOptions={{ hideAttribution: true }}
            >
              <Background />
              <Controls />
            </ReactFlow>
          )}
        </div>

        {/* Side panel: add + inspector */}
        <div className="flex flex-col gap-4 overflow-y-auto">
          <div className="rounded-xl border border-obsidian-stroke bg-obsidian-elevated p-4 space-y-3">
            <div className="text-sm font-semibold text-theme-foreground">Add step</div>
            <select
              className="select select-bordered select-sm w-full"
              value={addKind}
              onChange={(e) => setAddKind(e.target.value as WorkflowStepType)}
            >
              {ADDABLE_KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
            </select>

            {(addKind === 'AGENT' || addKind === 'PARALLEL') ? (
              <select
                className="select select-bordered select-sm w-full"
                value={addAgentId}
                onChange={(e) => setAddAgentId(e.target.value)}
              >
                <option value="">{addKind === 'PARALLEL' ? 'No agent (fan-out gate)' : 'Select agent…'}</option>
                {agents.map((a) => <option key={a.agentId} value={a.agentId}>{a.name}</option>)}
              </select>
            ) : addKind === 'WORKFLOW' ? (
              <select
                className="select select-bordered select-sm w-full"
                value={addWorkflowId}
                onChange={(e) => setAddWorkflowId(e.target.value)}
              >
                <option value="">Select sub-workflow…</option>
                {workflowOptions.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
              </select>
            ) : addKind === 'JOIN' ? (
              <div className="text-[10px] text-theme-muted">
                Fan-in barrier — no config. It waits for every incoming edge, then merges and continues.
              </div>
            ) : addKind === 'ROUTER' ? (
              <div className="space-y-2">
                <select
                  className="select select-bordered select-sm w-full"
                  value={routerSelector}
                  onChange={(e) => setRouterSelector(e.target.value as RouteSelectorType)}
                >
                  {ROUTE_SELECTORS.map((s) => <option key={s} value={s}>{s} selector</option>)}
                </select>
                {routerSelector !== 'HITL' && (
                  <input
                    type="text"
                    className="input input-bordered input-sm w-full"
                    placeholder={routerSelector === 'RULE' ? 'JSONPath, e.g. $.decision' : 'classification prompt'}
                    value={routerExpr}
                    onChange={(e) => setRouterExpr(e.target.value)}
                  />
                )}
                <input
                  type="text"
                  className="input input-bordered input-sm w-full"
                  placeholder="choice keys, comma-separated (e.g. approve,reject)"
                  value={routerChoices}
                  onChange={(e) => setRouterChoices(e.target.value)}
                />
                <div className="text-[10px] text-theme-muted">
                  Draw an edge from this node to label it with one of these choice keys.
                </div>
              </div>
            ) : (
              <input
                type="text"
                className="input input-bordered input-sm w-full"
                placeholder={
                  addKind === 'CONDITION' ? 'e.g. contains:approve' :
                  addKind === 'WEBHOOK' ? 'https://…' :
                  addKind === 'FUNCTION' ? 'registered function key, e.g. uppercase' : 'e.g. max:3|until:done'
                }
                value={addExpr}
                onChange={(e) => setAddExpr(e.target.value)}
              />
            )}

            <Button variant="primary" size="sm" fullWidth disabled={busy} onClick={() => void handleAdd()}>
              <LuPlus size={14} /> Add step (order {nextStepOrder})
            </Button>
          </div>

          <div className="rounded-xl border border-obsidian-stroke bg-obsidian-elevated p-4 space-y-2">
            <div className="text-sm font-semibold text-theme-foreground">Inspector</div>
            {selectedEdge ? (
              <>
                <div className="text-xs text-theme-muted">Edge</div>
                <div className="text-sm text-theme-foreground">
                  {edgeSourceData?.title ?? selectedEdge.source} → {edgeTargetData?.title ?? selectedEdge.target}
                </div>
                {selectedEdgePersisted ? (
                  <>
                    <div className="text-xs text-theme-muted mt-2">
                      Port: {edgeCurrentPort
                        ? <span className="font-mono text-theme-foreground">{edgeCurrentPort}</span>
                        : <span className="italic">unconditional</span>}
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {fixedPorts(edgeSourceKind).map((p) => (
                        <Button
                          key={p} variant="outline" size="sm" disabled={busy || p === edgeCurrentPort}
                          onClick={() => void relabelEdge(p)}
                        >
                          {p}
                        </Button>
                      ))}
                      <Button
                        variant="outline" size="sm" disabled={busy || edgeCurrentPort === null}
                        onClick={() => void relabelEdge(null)}
                      >
                        unconditional
                      </Button>
                    </div>
                    {edgeSourceKind === 'ROUTER' && (
                      <div className="flex gap-2 mt-1">
                        <input
                          type="text"
                          className="input input-bordered input-sm flex-1"
                          placeholder="choice key (e.g. approve)"
                          value={edgeRouterKey}
                          onChange={(e) => setEdgeRouterKey(e.target.value)}
                        />
                        <Button
                          variant="primary" size="sm" disabled={busy || !edgeRouterKey.trim()}
                          onClick={() => { const k = edgeRouterKey.trim(); setEdgeRouterKey(''); void relabelEdge(k); }}
                        >
                          Set
                        </Button>
                      </div>
                    )}
                    <Button
                      variant="danger" size="sm" fullWidth disabled={busy}
                      onClick={() => { void handleEdgesDelete([selectedEdge]); setSelectedEdgeId(null); }}
                      className="mt-2"
                    >
                      <LuTrash2 size={14} /> Delete edge
                    </Button>
                  </>
                ) : (
                  <div className="text-xs text-theme-muted mt-2">
                    This edge is derived from step order. Draw an explicit edge to make the graph editable.
                  </div>
                )}
              </>
            ) : selectedData ? (
              <>
                <div className="text-xs text-theme-muted">Kind</div>
                <div className="text-sm text-theme-foreground">{selectedData.kind} · {selectedData.subtitle}</div>

                {selectedIsRouter ? (
                  <>
                    <div className="text-xs text-theme-muted mt-2">Selector</div>
                    <select
                      className="select select-bordered select-sm w-full"
                      value={editRouterSelector}
                      onChange={(e) => setEditRouterSelector(e.target.value as RouteSelectorType)}
                    >
                      {ROUTE_SELECTORS.map((s) => <option key={s} value={s}>{s} selector</option>)}
                    </select>
                    {editRouterSelector !== 'HITL' && (
                      <input
                        type="text"
                        className="input input-bordered input-sm w-full"
                        placeholder={editRouterSelector === 'RULE' ? 'JSONPath, e.g. $.decision' : 'classification prompt'}
                        value={editRouterExpr}
                        onChange={(e) => setEditRouterExpr(e.target.value)}
                      />
                    )}
                    <div className="text-xs text-theme-muted mt-2">Choice keys</div>
                    <input
                      type="text"
                      className="input input-bordered input-sm w-full"
                      placeholder="comma-separated (e.g. approve,reject)"
                      value={editRouterChoices}
                      onChange={(e) => setEditRouterChoices(e.target.value)}
                    />
                    <div className="text-[10px] text-theme-muted">
                      Draw an edge from this node to label it with one of these choice keys.
                    </div>
                  </>
                ) : selectedData.kind === 'JOIN' ? (
                  <div className="text-[10px] text-theme-muted mt-2">
                    Fan-in barrier — no config. It waits for every incoming edge, then merges and continues.
                  </div>
                ) : (
                  <>
                    <div className="text-xs text-theme-muted mt-2">
                      {selectedIsAgent ? 'Agent' : selectedData.kind === 'WORKFLOW' ? 'Sub-workflow' : 'Expression'}
                    </div>
                    {selectedIsAgent ? (
                      <select
                        className="select select-bordered select-sm w-full"
                        value={editValue}
                        onChange={(e) => setEditValue(e.target.value)}
                      >
                        <option value="">
                          {selectedData.kind === 'PARALLEL' ? 'No agent (fan-out gate)' : 'Select agent…'}
                        </option>
                        {agents.map((a) => <option key={a.agentId} value={a.agentId}>{a.name}</option>)}
                      </select>
                    ) : selectedData.kind === 'WORKFLOW' ? (
                      <select
                        className="select select-bordered select-sm w-full"
                        value={editValue}
                        onChange={(e) => setEditValue(e.target.value)}
                      >
                        <option value="">Select sub-workflow…</option>
                        {workflowOptions.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
                      </select>
                    ) : (
                      <input
                        type="text"
                        className="input input-bordered input-sm w-full"
                        placeholder={
                          selectedData.kind === 'CONDITION' ? 'e.g. contains:approve' :
                          selectedData.kind === 'WEBHOOK' ? 'https://…' :
                          selectedData.kind === 'FUNCTION' ? 'registered function key, e.g. uppercase' :
                          selectedData.kind === 'LOOP' ? 'e.g. max:3|until:done' : ''
                        }
                        value={editValue}
                        onChange={(e) => setEditValue(e.target.value)}
                      />
                    )}
                  </>
                )}

                <Button
                  variant="primary" size="sm" fullWidth disabled={busy || !inspectorDirty}
                  onClick={() => void handleUpdateStep()}
                  className="mt-2"
                >
                  <LuSave size={14} /> Save
                </Button>
                <Button
                  variant="danger" size="sm" fullWidth disabled={busy}
                  onClick={() => selectedId && void deleteSteps([selectedId])}
                  className="mt-2"
                >
                  <LuTrash2 size={14} /> Delete step
                </Button>
              </>
            ) : (
              <div className="text-sm text-theme-muted">
                Click a node to select it. Shift/⌘-click to select several. Press
                <kbd className="kbd kbd-xs mx-1">Delete</kbd> (or use this panel) to remove the selection.
              </div>
            )}
          </div>

          <div className="rounded-xl border border-obsidian-stroke bg-obsidian-elevated p-3 space-y-2">
            <div className="text-sm font-semibold text-theme-foreground">Overview</div>
            <GraphOverview nodes={nodes} edges={edges} selectedId={selectedId} />
          </div>

          <div className="text-[11px] leading-relaxed text-theme-muted px-1">
            Drag from a node's bottom handle to another to add a DAG edge. From a CONDITION,
            ROUTER, or LOOP node you'll choose the outgoing <span className="text-theme-foreground">port</span>
            (true/false, a router choice key, loop/exit) — that label routes execution. Select an edge
            and press <kbd className="kbd kbd-xs mx-0.5">Delete</kbd> to remove it.
          </div>
        </div>
      </div>

      {/* Port picker — choose which outgoing port a new edge from a branching node represents */}
      <Dialog
        isOpen={pendingConn !== null}
        setIsOpen={(open) => { if (!open) setPendingConn(null); }}
        title={`Edge port · ${pendingKind.toLowerCase()} node`}
        severity="info"
        confirmLabel="Unconditional"
        cancelLabel="Cancel"
        shouldCloseOnConfirm={false}
        onConfirm={() => {
          const c = pendingConn;
          setPendingConn(null);
          if (c) void persistEdge(c, null);
        }}
        onCancel={() => setPendingConn(null)}
      >
        <div className="space-y-4 pt-2">
          <p className="text-xs text-theme-muted">
            Which outgoing port does this edge represent? The label decides which branch the
            scheduler activates. Choose <span className="font-mono">Unconditional</span> for a plain edge.
          </p>

          {fixedPorts(pendingKind).length > 0 && (
            <div className="flex flex-wrap gap-2">
              {fixedPorts(pendingKind).map((p) => (
                <Button
                  key={p}
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    const c = pendingConn;
                    setPendingConn(null);
                    if (c) void persistEdge(c, p);
                  }}
                >
                  {p}
                </Button>
              ))}
            </div>
          )}

          {pendingKind === 'ROUTER' && (
            <div className="flex gap-2">
              <input
                type="text"
                className="input input-bordered input-sm flex-1"
                placeholder="choice key (e.g. approve)"
                value={routerKey}
                onChange={(e) => setRouterKey(e.target.value)}
              />
              <Button
                variant="primary"
                size="sm"
                disabled={busy || !routerKey.trim()}
                onClick={() => {
                  const c = pendingConn;
                  const k = routerKey.trim();
                  setPendingConn(null);
                  if (c) void persistEdge(c, k);
                }}
              >
                Add
              </Button>
            </div>
          )}
        </div>
      </Dialog>

      {/* Run workflow — collects the initial input, then hands off to run history */}
      <Dialog
        isOpen={runOpen}
        setIsOpen={(open) => { if (!open) setRunOpen(false); }}
        title="Run workflow"
        severity="info"
        confirmLabel={running ? 'Starting…' : 'Run'}
        cancelLabel="Cancel"
        shouldCloseOnConfirm={false}
        onConfirm={() => void handleRun()}
        onCancel={() => setRunOpen(false)}
      >
        <div className="space-y-3 pt-2">
          <div>
            <label className="text-xs text-theme-muted">Initial input</label>
            <textarea
              className="textarea textarea-bordered textarea-sm w-full mt-1"
              rows={3}
              placeholder="The input passed to the first step…"
              value={runInput}
              onChange={(e) => setRunInput(e.target.value)}
            />
          </div>
          <div>
            <label className="text-xs text-theme-muted">Session ID (optional)</label>
            <input
              type="text"
              className="input input-bordered input-sm w-full mt-1"
              placeholder="auto-created if blank"
              value={runSessionId}
              onChange={(e) => setRunSessionId(e.target.value)}
            />
          </div>
          {runError && <Alert severity="error">{runError}</Alert>}
        </div>
      </Dialog>
    </PageContainer>
  );
}

export default function WorkflowGraphEditorPage() {
  return (
    <ReactFlowProvider>
      <GraphEditor />
    </ReactFlowProvider>
  );
}
