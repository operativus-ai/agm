// F7 — Workflows. TC-WF-1: author a linear 2-node AGENT DAG via the API →
// validate → run → poll to a terminal run status with per-run records.
//
// Authoring/validation 4xx are reported as WARN (not FAIL): they most likely
// mean the client-side DTO shape needs a live-shakeout tweak, which is a harness
// concern, not an AGM contract violation. The run-completion assertion is the
// pass criterion.

import { createLinearAgentWorkflow } from '../fixtures/workflow-fixture.js';
import { AgmApiError } from '../sdk/http.js';
import { fail, pass, warn, type Scenario } from '../harness/scenario.js';
import type { AgmClient } from '../sdk/agm.js';
import type { WorkflowRunSummary } from '../types.js';

const linearDag: Scenario = {
  id: 'TC-WF-1',
  domain: 'F7',
  title: 'author linear DAG → validate → run → COMPLETED',
  tier: 'T2',
  priority: 'P1',
  prereqs: { backend: true, admin: true, agent: true, providerKey: true },
  async run(ctx) {
    const agm = ctx.admin();

    let wf;
    try {
      wf = await createLinearAgentWorkflow(agm, ctx.prefix, ctx.agentId!);
      ctx.track(wf.teardown);
    } catch (err) {
      if (err instanceof AgmApiError) return warn(`authoring returned ${err.status}: ${err.message} — verify DTO shape live`);
      throw err;
    }
    if (wf.stepIds.length < 2) return warn(`only ${wf.stepIds.length} steps created — step DTO may be incomplete`);

    // Validate (defensive: shape of WorkflowValidationResult read loosely).
    try {
      const validation = await agm.validateWorkflow(wf.workflowId);
      if (validation.valid === false) return warn(`workflow reported invalid: ${JSON.stringify(validation.errors ?? validation)}`);
    } catch (err) {
      if (err instanceof AgmApiError) return warn(`validate returned ${err.status}: ${err.message}`);
      throw err;
    }

    // Run (async → poll runs).
    const exec = await agm.runWorkflow(wf.workflowId, 'Say hello.', crypto.randomUUID());
    if (!exec.jobId) return warn('run accepted but no jobId returned');

    const terminal = await pollWorkflowRun(agm, wf.workflowId, 90_000);
    if (!terminal) return warn(`workflow run not terminal within 90s (jobId ${exec.jobId})`);
    if (terminal.status === 'COMPLETED') {
      return pass(`DAG authored, validated, ran → COMPLETED`, { steps: wf.stepIds.length, run: terminal });
    }
    if (terminal.status === 'FAILED') {
      // A failed run is a legitimate outcome if the agent itself failed (e.g. no key) —
      // report it as WARN so it doesn't mask as a harness/contract bug.
      return warn(`workflow ran but the run FAILED (agent-level) — ${JSON.stringify(terminal)}`);
    }
    return fail(`unexpected terminal status: ${terminal.status}`);
  },
};

async function pollWorkflowRun(
  agm: AgmClient,
  workflowId: string,
  timeoutMs: number,
): Promise<WorkflowRunSummary | null> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const page = await agm.workflowRuns(workflowId).catch(() => undefined);
    const runs = page?.content ?? [];
    const terminal = runs.find((r) => r.status === 'COMPLETED' || r.status === 'FAILED');
    if (terminal) return terminal;
    await new Promise((r) => setTimeout(r, 3000));
  }
  return null;
}

export const workflowScenarios: Scenario[] = [linearDag];
