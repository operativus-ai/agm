// F9 — Tools. TC-TOOL-1: the tool catalog is populated and categorized.
// (Tool *invocation* frames are asserted where a run uses a tool — Phase 2/4.)

import { fail, pass, warn, type Scenario } from '../harness/scenario.js';

const catalog: Scenario = {
  id: 'TC-TOOL-1',
  domain: 'F9',
  title: 'tool catalog (GET /api/tools)',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true, admin: true },
  async run(ctx) {
    const tools = await ctx.admin().listTools();
    if (!Array.isArray(tools)) return fail('tool catalog is not an array');
    if (tools.length === 0) return warn('tool catalog is empty');
    const categories = new Set(tools.map((t) => t.category).filter(Boolean));
    const named = tools.every((t) => !!t.id && !!t.label);
    if (!named) return fail('some tools missing id/label');
    return pass(`${tools.length} tools across ${categories.size} categories`, {
      tools: tools.length,
      categories: [...categories],
    });
  },
};

export const toolsScenarios: Scenario[] = [catalog];
