import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import {
  LuBot, LuGitBranch, LuGlobe, LuRepeat, LuGitMerge, LuWaypoints, LuMerge, LuSigma, LuLayers,
} from 'react-icons/lu';
import type { IconType } from 'react-icons';

/**
 * A single workflow step rendered as a React Flow node. One component covers every kind
 * for the flat first cut (single top/bottom handle); per-kind multi-ports for
 * CONDITION/ROUTER/LOOP arrive with the backend DAG engine (docs/plan/agm-dag-workflow.md).
 */
/** Per-node run status used by the read-only run viewer to color the canvas. */
export type NodeRunStatus = 'ok' | 'failed' | 'paused' | 'pending' | 'running';

export interface StepNodeData {
  kind: string;        // AGENT | CONDITION | PARALLEL | LOOP | WEBHOOK | ROUTER | JOIN | FUNCTION | WORKFLOW
  title: string;       // agent name / condition expression / loop config
  subtitle?: string;   // e.g. "step 3"
  status?: NodeRunStatus; // set only by the run viewer; undefined in the editor
  orphan?: boolean;    // editor validation overlay: unreachable from the start step
  [key: string]: unknown;
}

// Status ring + (dim) treatment for the run viewer. Empty for the editor (status undefined).
const STATUS_RING: Record<NodeRunStatus, string> = {
  ok:      'border-success-green ring-1 ring-success-green',
  failed:  'border-error-red ring-1 ring-error-red',
  paused:  'border-warning-amber ring-1 ring-warning-amber',
  running: 'border-info ring-1 ring-info animate-pulse',
  pending: 'opacity-40',
};

const KIND_META: Record<string, { icon: IconType; accent: string; label: string }> = {
  AGENT:     { icon: LuBot,       accent: 'border-l-agent-blue',  label: 'Agent' },
  CONDITION: { icon: LuGitBranch, accent: 'border-l-warning-amber', label: 'Condition' },
  PARALLEL:  { icon: LuGitMerge,  accent: 'border-l-success-green', label: 'Parallel' },
  LOOP:      { icon: LuRepeat,    accent: 'border-l-purple-400',  label: 'Loop' },
  WEBHOOK:   { icon: LuGlobe,     accent: 'border-l-cyan-400',    label: 'Webhook' },
  ROUTER:    { icon: LuWaypoints, accent: 'border-l-pink-400',    label: 'Router' },
  JOIN:      { icon: LuMerge,     accent: 'border-l-teal-400',    label: 'Join' },
  FUNCTION:  { icon: LuSigma,     accent: 'border-l-orange-400',  label: 'Function' },
  WORKFLOW:  { icon: LuLayers,    accent: 'border-l-indigo-400',  label: 'Workflow' },
};

function StepNodeImpl({ data, selected }: NodeProps) {
  const d = data as StepNodeData;
  const meta = KIND_META[d.kind] ?? KIND_META.AGENT;
  const Icon = meta.icon;

  // Run viewer colors by status; otherwise the editor's selected/orphan/default border applies.
  const stateClass = d.status
    ? STATUS_RING[d.status]
    : selected ? 'border-agent-blue ring-1 ring-agent-blue'
    : d.orphan ? 'border-warning-amber border-dashed ring-1 ring-warning-amber'
    : 'border-obsidian-stroke';

  return (
    <div
      className={[
        'rounded border border-l-2 bg-obsidian-elevated px-1.5 py-0.5 shadow-sm',
        'w-[80px] transition-colors',
        meta.accent,
        stateClass,
      ].join(' ')}
    >
      <Handle type="target" position={Position.Top} className="!bg-theme-muted" />
      <div className="flex items-center gap-1">
        <Icon className="shrink-0 text-theme-muted" size={9} />
        <div className="min-w-0">
          <div className="truncate text-[5.625px] font-medium leading-tight text-theme-foreground" title={d.title}>
            {d.title || '(unset)'}
          </div>
          <div className="text-[4.375px] uppercase tracking-wide text-theme-muted">
            {meta.label}{d.subtitle ? ` · ${d.subtitle}` : ''}
          </div>
        </div>
      </div>
      <Handle type="source" position={Position.Bottom} className="!bg-theme-muted" />
    </div>
  );
}

export const StepNode = memo(StepNodeImpl);
