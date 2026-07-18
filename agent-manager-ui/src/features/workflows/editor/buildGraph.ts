import type { Node, Edge } from '@xyflow/react';
import type { WorkflowStep, WorkflowEdge } from '../../../shared/types/orchestration';
import type { StepNodeData } from './WorkflowNodes';

/** Marks whether a rendered edge is backed by a persisted `workflow_edges` row. */
export interface GraphEdgeData {
  persisted: boolean;
  [key: string]: unknown;
}

const KNOWN_KINDS = ['AGENT', 'CONDITION', 'PARALLEL', 'LOOP', 'WEBHOOK', 'ROUTER', 'SEQUENTIAL',
  'JOIN', 'FUNCTION', 'WORKFLOW'];

function resolveKind(step: WorkflowStep): string {
  const raw = (step.stepType || step.action || 'AGENT').toUpperCase();
  return KNOWN_KINDS.includes(raw) ? raw : 'AGENT';
}

function resolveTitle(step: WorkflowStep, kind: string, agentNames: Map<string, string>): string {
  if (kind === 'CONDITION') return step.action && step.action !== 'CONDITION' ? step.action : (step.agentId || 'condition');
  if (kind === 'AGENT') return (step.agentId && agentNames.get(step.agentId)) || step.agentId || 'agent';
  return step.agentId || kind.toLowerCase();
}

/**
 * Project a workflow onto a React Flow graph for the editor.
 *
 * Edge source of truth:
 *  - When the backend serves explicit {@code workflow_edges} (DAG plan DAG-1), those are
 *    rendered verbatim — solid, deletable, with the port label (`condition`) shown. These
 *    are what the DAG executor will walk.
 *  - When there are none (legacy flat-list workflow), edges are *derived* from
 *    {@code stepOrder}: steps sharing an order are a parallel group, and every node in
 *    order N links from every node in order N-1 (fan-out / fan-in). Derived edges are
 *    dashed and non-deletable — drawing the first real edge replaces the whole derived
 *    view, mirroring the backend's "edges present ⇒ ignore step_order" contract.
 *
 * Positions are filled by ELK downstream.
 */
export function buildGraph(
  steps: WorkflowStep[],
  agentNames: Map<string, string>,
  persistedEdges: WorkflowEdge[] = [],
  orphanStepIds: Set<string> = new Set(),
): { nodes: Node[]; edges: Edge[] } {
  const sorted = [...steps].sort((a, b) => a.stepOrder - b.stepOrder);

  const nodes: Node[] = sorted.map((step) => {
    const kind = resolveKind(step);
    const data: StepNodeData = {
      kind,
      title: resolveTitle(step, kind, agentNames),
      subtitle: `step ${step.stepOrder}`,
      orphan: orphanStepIds.has(step.id),
    };
    return { id: step.id, type: 'step', position: { x: 0, y: 0 }, data };
  });

  const nodeIds = new Set(nodes.map((n) => n.id));

  // Explicit edges win: render exactly what the backend persists.
  if (persistedEdges.length > 0) {
    const edges: Edge[] = persistedEdges
      .filter((e) => nodeIds.has(e.fromStepId) && nodeIds.has(e.toStepId))
      .map((e) => ({
        id: e.id,
        source: e.fromStepId,
        target: e.toStepId,
        label: e.condition ?? undefined,
        animated: false,
        deletable: true,
        data: { persisted: true } satisfies GraphEdgeData,
      }));
    return { nodes, edges };
  }

  // Fallback: derive sequential edges from stepOrder (read-only).
  const byOrder = new Map<number, string[]>();
  for (const step of sorted) {
    const bucket = byOrder.get(step.stepOrder) ?? [];
    bucket.push(step.id);
    byOrder.set(step.stepOrder, bucket);
  }
  const orders = [...byOrder.keys()].sort((a, b) => a - b);

  const edges: Edge[] = [];
  for (let i = 1; i < orders.length; i++) {
    const prev = byOrder.get(orders[i - 1]) ?? [];
    const curr = byOrder.get(orders[i]) ?? [];
    for (const src of prev) {
      for (const tgt of curr) {
        edges.push({
          id: `${src}->${tgt}`,
          source: src,
          target: tgt,
          animated: false,
          deletable: false,
          style: { strokeDasharray: '4 3' },
          data: { persisted: false } satisfies GraphEdgeData,
        });
      }
    }
  }

  return { nodes, edges };
}
