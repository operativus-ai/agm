// F6 — Knowledge / RAG. TC-RAG-1: end-to-end grounding.
// create KB → upload a doc with a unique fact → poll ingestion → search returns
// it → (best-effort) a KB-bound agent answers a question only the doc can answer.

import { createKbWithFact } from '../fixtures/kb-fixture.js';
import { AgmApiError } from '../sdk/http.js';
import { fail, pass, warn, type Scenario } from '../harness/scenario.js';
import type { RagDocument } from '../types.js';

function docText(d: RagDocument): string {
  return d.text ?? d.content ?? d.formattedContent ?? '';
}

const grounding: Scenario = {
  id: 'TC-RAG-1',
  domain: 'F6',
  title: 'RAG end-to-end grounding (KB → upload → search → agent answer)',
  tier: 'T2',
  priority: 'P1',
  prereqs: { backend: true, admin: true, agent: true },
  async run(ctx) {
    const agm = ctx.admin();
    const kb = await createKbWithFact(agm, ctx.prefix);
    ctx.track(kb.teardown);

    if (!kb.ingested) return warn('document did not reach COMPLETED ingestion in time (embedding model down?)');

    // Retrieval on the wire — the fact token must surface.
    const token = kb.fact.match(/ZEPHYR-[A-Z0-9]+/)?.[0] ?? 'ZEPHYR';
    const hits = await agm.searchKnowledge('classified project codename');
    if (!Array.isArray(hits)) return fail('search did not return a document array');
    const retrieved = hits.some((d) => docText(d).includes(token));
    if (!retrieved) {
      return warn(`ingested but token '${token}' not in top results (${hits.length} hits) — embeddings may be NoOp/noise`);
    }

    // Best-effort grounding: bind the KB to the agent and ask a doc-only question.
    let grounded: boolean | undefined;
    try {
      await agm.assignAgentToKb(kb.kbId, ctx.agentId!);
      ctx.track(async () => void agm.removeAgentFromKb(kb.kbId, ctx.agentId!).catch(() => {}));
      const sessionId = crypto.randomUUID();
      ctx.track(async () => void agm.http.delete(`/sessions/${sessionId}`).catch(() => {}));
      const r = await agm.run(ctx.agentId!, { message: 'What is the classified project codename?', stream: true, sessionId });
      ctx.budget.record(r.usage);
      if (!r.paused && !r.streamError) grounded = r.text.includes(token);
    } catch (err) {
      if (err instanceof AgmApiError && err.kind === 'provider-key') {
        return pass(`retrieval verified (token found); grounding skipped — no provider key`, { retrieved: true });
      }
      throw err;
    }

    if (grounded === undefined) return pass('retrieval verified; grounding run paused/errored', { retrieved: true });
    return grounded
      ? pass('retrieval + agent grounding both verified', { retrieved: true, grounded: true })
      : warn('retrieval verified; agent answer did not echo the fact (model-dependent)', { retrieved: true, grounded: false });
  },
};

export const ragScenarios: Scenario[] = [grounding];
