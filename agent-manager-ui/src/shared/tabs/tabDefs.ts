import type { ReactNode } from 'react';

/** Declarative tab definition — the contribution unit for edition tab seams
 *  (ObservabilityPage groups, ApprovalsPage). */
export interface TabDef {
  slug: string;
  label: string;
  content: ReactNode;
}
