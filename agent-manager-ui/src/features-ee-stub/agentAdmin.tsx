import type React from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import type { AgentConfig } from '../shared/types/api';

/**
 * Core stub for the `@ee/agent-admin` seam. The enterprise web build aliases
 * this module to its real implementation (bulk selection / bulk actions and
 * the developer-metrics panel); Core renders nothing.
 */

export interface AgentBulkContext {
    agents: AgentConfig[];
    refresh: () => Promise<void> | void;
    showMessage: (text: string, type: 'success' | 'error' | 'info' | 'warning') => void;
}

export interface AgentBulkControls {
    selectionColumn: ColumnDef<AgentConfig, unknown> | null;
    toolbar: React.ReactNode;
}

export function useAgentBulk(_ctx: AgentBulkContext): AgentBulkControls {
    return { selectionColumn: null, toolbar: null };
}

export const EeAgentDxMetricsPanel: React.ComponentType<{ agentId: string }> | null = null;
