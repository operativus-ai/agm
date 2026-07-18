import { ApiClient } from './client';
import type { WorkflowTemplate } from '../types/orchestration';

export interface AgentTemplateDTO {
  id: string;
  name: string;
  description: string;
  icon: string;
  defaultModel?: string;
  defaultTemperature?: number;
  finOpsRiskTier?: string;
  defaultToolCategories?: string[];
  defaultTools?: string[];
  requiresPiiRedaction?: boolean;
  memoryEnabled?: boolean;
  securityTier?: number;
  systemPromptMode?: string;
  enforceJsonOutput?: boolean;
}

export interface AppConfig {
  os_id: string;
  name: string;
  version: string;
  agents: unknown[];
  teams: unknown[];
  workflows: unknown[];
  knowledge_bases: unknown[];
}

export const configApi = {
  getConfig: () => ApiClient.get<AppConfig>('/config'),
  getAgentTemplates: () => ApiClient.get<AgentTemplateDTO[]>('/config/templates'),
  getWorkflowTemplates: () => ApiClient.get<WorkflowTemplate[]>('/config/workflow-templates'),
};
