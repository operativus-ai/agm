import ELK from 'elkjs/lib/elk.bundled.js';
import type { Node, Edge } from '@xyflow/react';

/**
 * Auto-layout seam for the DAG editor.
 *
 * ELK (Eclipse Layout Kernel) is the chosen engine — it supports typed ports, nested
 * (sub-workflow) hierarchy, and clean loop back-edge routing, which Dagre cannot
 * (see docs/plan/agm-dag-workflow.md §5.2). This first cut uses the main-thread
 * `elk.bundled` build; swap to the `elk-worker` build behind this same function once
 * graphs grow large. Callers stay layout-agnostic: pass React Flow nodes/edges, get
 * the same nodes back with `position` filled in.
 */

const elk = new ELK();

export const NODE_WIDTH = 80;
export const NODE_HEIGHT = 28;

const LAYOUT_OPTIONS: Record<string, string> = {
  'elk.algorithm': 'layered',
  'elk.direction': 'DOWN',
  'elk.layered.spacing.nodeNodeBetweenLayers': '32',
  'elk.spacing.nodeNode': '18',
  // Harmless for the flat first cut; load-bearing once CONDITION/ROUTER/LOOP ports and
  // sub-workflow compound nodes land (the reason ELK was chosen over Dagre).
  'elk.layered.portConstraints': 'FIXED_ORDER',
  'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
  'elk.edgeRouting': 'ORTHOGONAL',
};

export async function layoutGraph(
  nodes: Node[],
  edges: Edge[],
): Promise<{ nodes: Node[]; edges: Edge[] }> {
  if (nodes.length === 0) return { nodes, edges };

  const graph = {
    id: 'root',
    layoutOptions: LAYOUT_OPTIONS,
    children: nodes.map((n) => ({ id: n.id, width: NODE_WIDTH, height: NODE_HEIGHT })),
    edges: edges.map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
  };

  const laidOut = await elk.layout(graph);
  const positions = new Map<string, { x: number; y: number }>();
  for (const child of laidOut.children ?? []) {
    positions.set(child.id, { x: child.x ?? 0, y: child.y ?? 0 });
  }

  return {
    nodes: nodes.map((n) => ({ ...n, position: positions.get(n.id) ?? n.position })),
    edges,
  };
}
