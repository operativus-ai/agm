import { RunStatus } from './enums';

export interface EvaluationSuite {
  id: string;
  name: string;
  description: string;
  createdAt?: string;
  updatedAt?: string;
  cases?: EvaluationCase[];
  runs?: EvaluationRun[];
}

export interface EvaluationCase {
  id: string;
  suiteId: string;
  name: string;
  input: string;
  expectedOutput?: string;
  systemPromptOverride?: string;
  createdAt?: string;
  updatedAt?: string;
}

export const ScorerType = {
  EXACT_MATCH: 'EXACT_MATCH',
  REGEX_MATCH: 'REGEX_MATCH',
  SEMANTIC_SIMILARITY: 'SEMANTIC_SIMILARITY',
  LLM_JUDGE: 'LLM_JUDGE'
} as const;

export type ScorerType = typeof ScorerType[keyof typeof ScorerType];

export interface EvaluationRun {
  id: string;
  suiteId: string;
  agentId: string;
  status: RunStatus;
  startedAt?: string;
  completedAt?: string;
  totalCases?: number;
  passedCases?: number;
  failedCases?: number;
  averageScore?: number;
  averageLatencyMs?: number;
  metrics?: Record<string, any>;
  results?: EvaluationResult[];
}



export interface EvaluationResult {
  id: string;
  runId: string;
  caseId: string;
  actualOutput?: string;
  score: number;
  passed: boolean;
  reasoning?: string;
  error?: string;
  durationMs: number;
  metadata?: Record<string, any>;
}

export interface CreateEvaluationSuiteRequest {
  name: string;
  description: string;
}

export interface CreateEvaluationCaseRequest {
  name: string;
  input: string;
  expectedOutput?: string;
  systemPromptOverride?: string;
}
