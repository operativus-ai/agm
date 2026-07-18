import type { Node, Edge } from '@xyflow/react';
import { NODE_WIDTH, NODE_HEIGHT } from './layoutGraph';

/**
 * A lightweight, self-contained graph overview rendered OUTSIDE the React Flow canvas
 * (in the editor's side panel). React Flow's built-in {@code <MiniMap>} is coupled to the
 * flow's internal DOM measurement and emits NaN paths when mounted outside the canvas, so we
 * draw our own from the node positions + edges already in component state.
 */
export function GraphOverview({
  nodes,
  edges,
  selectedId,
  height = 130,
}: {
  nodes: Node[];
  edges: Edge[];
  selectedId: string | null;
  height?: number;
}) {
  if (nodes.length === 0) {
    return <div className="text-xs text-theme-muted">No nodes yet.</div>;
  }

  const pos = new Map(nodes.map((n) => [n.id, n.position]));
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const n of nodes) {
    minX = Math.min(minX, n.position.x);
    minY = Math.min(minY, n.position.y);
    maxX = Math.max(maxX, n.position.x + NODE_WIDTH);
    maxY = Math.max(maxY, n.position.y + NODE_HEIGHT);
  }
  const pad = 16;
  const vbW = Math.max(1, maxX - minX + pad * 2);
  const vbH = Math.max(1, maxY - minY + pad * 2);
  const viewBox = `${minX - pad} ${minY - pad} ${vbW} ${vbH}`;

  return (
    <svg
      width="100%"
      height={height}
      viewBox={viewBox}
      preserveAspectRatio="xMidYMid meet"
      style={{ display: 'block' }}
      role="img"
      aria-label="Workflow graph overview"
    >
      {edges.map((e) => {
        const s = pos.get(e.source);
        const t = pos.get(e.target);
        if (!s || !t) return null;
        return (
          <line
            key={e.id}
            x1={s.x + NODE_WIDTH / 2}
            y1={s.y + NODE_HEIGHT}
            x2={t.x + NODE_WIDTH / 2}
            y2={t.y}
            stroke="#52525b"
            strokeWidth={2}
          />
        );
      })}
      {nodes.map((n) => {
        const sel = n.id === selectedId;
        return (
          <rect
            key={n.id}
            x={n.position.x}
            y={n.position.y}
            width={NODE_WIDTH}
            height={NODE_HEIGHT}
            rx={4}
            ry={4}
            fill={sel ? '#3b82f6' : '#3f3f46'}
            stroke={sel ? '#60a5fa' : '#52525b'}
            strokeWidth={2}
          />
        );
      })}
    </svg>
  );
}
