import type { ReactNode } from 'react';
import { HumanReviewQueue } from './components/HumanReviewQueue';

export interface ApprovalsTabDef {
  value: string;
  label: string;
  /** Self-contained tab body. Absent for the two queue tabs, whose body (shared
   *  DataTable + selection state) lives in ApprovalsPage itself. */
  render?: () => ReactNode;
  /** Edition feature key — tab hidden when the license lacks it. */
  featureKey?: string;
}

export const APPROVALS_TABS: ApprovalsTabDef[] = [
  { value: 'PENDING', label: 'Pending Inbox' },
  { value: 'RESOLVED', label: 'Resolved History' },
  { value: 'HUMAN_REVIEW', label: 'Human Review', render: () => <HumanReviewQueue /> },
];

/**
 * Edition tabs (e.g. Escalations/Incident) append here — same data-level merge seam as
 * the ObservabilityPage TabDef registry. (agm-core-oss-execution.md §5.3)
 */
export function mergeApprovalsTabs(
  core: ApprovalsTabDef[],
  contributed: ApprovalsTabDef[],
): ApprovalsTabDef[] {
  return [...core, ...contributed];
}
