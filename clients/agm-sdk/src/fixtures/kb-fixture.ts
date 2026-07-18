// Knowledge-base fixture: create a KB, upload a text doc carrying a unique fact,
// poll ingestion to COMPLETED. Returns the ids + a teardown (delete KB). Used by
// the RAG scenarios. Self-cleaning via ctx.track.

import type { AgmClient } from '../sdk/agm.js';

export interface KbFixture {
  kbId: string;
  fact: string;
  /** Was ingestion observed COMPLETED before the poll timeout? */
  ingested: boolean;
  teardown: () => Promise<void>;
}

/** Poll GET /knowledge?knowledgeBaseId= until all contents are terminal (COMPLETED/FAILED). */
async function waitIngested(agm: AgmClient, kbId: string, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const page = await agm.listKnowledge(kbId).catch(() => undefined);
    const items = page?.content ?? [];
    if (items.length > 0 && items.every((c) => c.status === 'COMPLETED' || c.status === 'FAILED')) {
      return items.some((c) => c.status === 'COMPLETED');
    }
    await sleep(1500);
  }
  return false;
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

export async function createKbWithFact(
  agm: AgmClient,
  prefix: string,
  opts: { timeoutMs?: number } = {},
): Promise<KbFixture> {
  // A distinctive, model-un-guessable fact so retrieval/grounding is provable.
  const token = prefix.replace(/[^a-zA-Z0-9]/g, '').slice(0, 12).toUpperCase();
  const fact = `The classified project codename is ZEPHYR-${token}.`;
  const kb = await agm.createKnowledgeBase(`${prefix}kb`, 'test-client RAG fixture');
  const teardown = async () => {
    await agm.deleteKnowledgeBase(kb.id).catch(() => {});
  };

  const file = new File(
    [`${fact}\nThis document exists solely to verify retrieval-augmented grounding.`],
    `${prefix}doc.txt`,
    { type: 'text/plain' },
  );
  await agm.uploadDocs(kb.id, [file]);
  const ingested = await waitIngested(agm, kb.id, opts.timeoutMs ?? 45_000);

  return { kbId: kb.id, fact, ingested, teardown };
}
