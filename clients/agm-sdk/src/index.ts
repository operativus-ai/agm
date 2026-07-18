// @agm/sdk — the browser + node safe engine for consuming Agent Manager's
// REST + SSE surface. Shared by the headless CLI (test-client) and the live UI
// (console). No config/fs/process dependencies — the consumer supplies baseUrl,
// credentials, and flag overrides.

// Types
export * from './types.js';

// SDK core
export { AgmClient } from './sdk/agm.js';
export {
  HttpClient,
  AgmApiError,
  classifyError,
  problemDetail,
  type AgmErrorKind,
} from './sdk/http.js';
export { createSseParser, type SseParser, type SseParserEvents } from './sdk/sse.js';
export { collectRun, streamRun, type RunResult, type CollectOptions, type StreamHandlers } from './sdk/stream.js';

// Harness
export {
  Ctx,
  pass,
  fail,
  warn,
  skip,
  type Scenario,
  type ScenarioResult,
  type Prereqs,
  type Tier,
  type Priority,
  type Outcome,
} from './harness/scenario.js';
export {
  runScenarios,
  envCaps,
  type Filters,
  type EnvCaps,
  type RunOptions,
  type ScenarioReport,
} from './harness/runner.js';
export { Budget, BudgetExceededError } from './harness/budget.js';
export { detectFlags, describeFlags, type FlagState, type FlagOverrides } from './harness/flags.js';

// Fixtures + identities
export {
  resolveIdentities,
  poolFromClients,
  provisionUser,
  type IdentityPool,
  type ResolvedIdentity,
  type IdentityCreds,
  type CredsPool,
} from './fixtures/identities.js';
export { createKbWithFact, type KbFixture } from './fixtures/kb-fixture.js';
export { createLinearAgentWorkflow, type WorkflowFixture } from './fixtures/workflow-fixture.js';
export { sweepAgents, sweepAll, runPrefix, PREFIX_ROOT, type SweepResult } from './fixtures/cleanup.js';

// Scenario registry
export { allScenarios } from './scenarios/index.js';
export { offlineScenarios } from './scenarios/offline.js';
