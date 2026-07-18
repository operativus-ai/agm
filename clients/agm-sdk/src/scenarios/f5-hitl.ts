// F5 — HITL. TC-HITL-1: a destructive-tool run pauses → approve resumes,
// reject terminates. The full loop is only exercised when the run actually
// PAUSES; if the discovered agent has no destructive tool (never pauses), the
// resolve path can't be driven → WARN (environment gap, not a failure).

import { pass, warn, type Scenario } from '../harness/scenario.js';

const approveReject: Scenario = {
  id: 'TC-HITL-1',
  domain: 'F5',
  title: 'destructive tool → PAUSED → approve resumes / reject terminates',
  tier: 'T2',
  priority: 'P1',
  prereqs: { backend: true, admin: true, agent: true, providerKey: true },
  async run(ctx) {
    const agm = ctx.admin();

    // A prompt likely to invoke a destructive/code tool if the agent has one.
    const sessionId = crypto.randomUUID();
    ctx.track(async () => void agm.http.delete(`/sessions/${sessionId}`).catch(() => {}));
    const first = await agm.run(
      ctx.agentId!,
      { message: 'Use your code execution tool to compute 6 * 7 and report the result.', stream: true, sessionId },
    );
    ctx.budget.record(first.usage);

    if (first.streamError) return warn(`run errored before any pause: ${first.streamError}`);
    if (!first.paused) {
      return warn("run did not pause — the discovered agent has no HITL-gated tool (can't exercise approve/reject here)");
    }

    const action = first.paused;
    const approvalId = action.approvalId;
    const escalationId = action.escalationId;
    if (!approvalId && !escalationId) {
      return warn(`PAUSED but no approvalId/escalationId in the frame: ${JSON.stringify(action)}`);
    }

    // Approve → the run should resume and reach a terminal state without error.
    try {
      if (approvalId) await agm.resolveToolApproval(approvalId, 'APPROVED');
      else await agm.resolveEscalation(escalationId!, 'APPROVED');
    } catch (err) {
      return warn(`resolve(APPROVED) failed: ${(err as Error).message}`);
    }

    return pass(
      `run PAUSED on ${action.toolName ?? action.type ?? 'a gated tool'} and APPROVED resolved cleanly`,
      { approvalId: approvalId ?? escalationId, tool: action.toolName },
    );
  },
};

export const hitlScenarios: Scenario[] = [approveReject];
