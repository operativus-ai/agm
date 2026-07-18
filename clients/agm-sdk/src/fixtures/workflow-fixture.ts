// Workflow fixture: build a minimal linear 2-node AGENT DAG (step0 → step1)
// against a given agent. Returns the workflow id + step ids + a teardown.
// Used by the workflow scenarios; extended with more node kinds in Phase 3.

import type { AgmClient } from '../sdk/agm.js';

export interface WorkflowFixture {
  workflowId: string;
  stepIds: string[];
  teardown: () => Promise<void>;
}

/** Create workflow → add two AGENT steps → connect with an edge. */
export async function createLinearAgentWorkflow(
  agm: AgmClient,
  prefix: string,
  agentId: string,
): Promise<WorkflowFixture> {
  const wf = await agm.createWorkflow(`${prefix}wf`, 'test-client linear DAG fixture');
  const teardown = async () => {
    await agm.deleteWorkflow(wf.id).catch(() => {});
  };

  const s0 = await agm.addWorkflowStep(wf.id, { stepOrder: 0, agentId });
  const s1 = await agm.addWorkflowStep(wf.id, { stepOrder: 1, agentId });
  const stepIds = [s0.id, s1.id].filter((x): x is string => !!x);

  if (stepIds.length === 2) {
    await agm.addWorkflowEdge(wf.id, { fromStepId: stepIds[0], toStepId: stepIds[1] });
  }

  return { workflowId: wf.id, stepIds, teardown };
}
