// Scenario registry. Phases append their scenario arrays here; the runner
// discovers everything exported as `allScenarios`.

import type { Scenario } from '../harness/scenario.js';
import { offlineScenarios } from './offline.js';
import { smokeCoreScenarios } from './smoke-core.js';
// Phase 1 — P1 breadth (one scenario per remaining domain).
import { runsScenarios } from './f3-runs.js';
import { sessionsScenarios } from './f4-sessions.js';
import { hitlScenarios } from './f5-hitl.js';
import { ragScenarios } from './f6-rag.js';
import { workflowScenarios } from './f7-workflows.js';
import { toolsScenarios } from './f9-tools.js';
import { governanceScenarios } from './f11-governance.js';

export const allScenarios: Scenario[] = [
  ...offlineScenarios,
  ...smokeCoreScenarios,
  ...runsScenarios,
  ...sessionsScenarios,
  ...hitlScenarios,
  ...ragScenarios,
  ...workflowScenarios,
  ...toolsScenarios,
  ...governanceScenarios,
  // Phase 2+: additional depth scenarios appended here.
];
