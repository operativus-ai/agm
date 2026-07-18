/**
 * Static lookup maps for the team wizard/form, extracted from TeamDetailsPage.
 */

/** Backend icon string → emoji, for the template picker cards. */
export const TEMPLATE_ICONS: Record<string, string> = {
    'headphones': '\u{1F3A7}',
    'file-text': '\u{1F4DD}',
    'search': '\u{1F50D}',
    'zap': '\u{26A1}',
    'pen-tool': '\u{270F}\u{FE0F}',
    'scale': '\u{2696}\u{FE0F}',
    'clipboard-list': '\u{1F4CB}',
    'crown': '\u{1F451}',
    'settings': '\u{2699}\u{FE0F}',
};

export const TEAM_MODE_LABELS: Record<string, string> = {
    'ROUTER': 'Router',
    'SEQUENTIAL': 'Sequential',
    'SWARM': 'Swarm',
    'PLANNER': 'Planner',
    'COORDINATOR': 'Coordinator',
};

export const TEAM_MODE_DESCRIPTIONS: Record<string, string> = {
    'ROUTER': 'LLM classifies intent and routes to best-fit agent',
    'SEQUENTIAL': 'Chain agents in order; each receives prior output',
    'SWARM': 'LLM decomposes into subtasks for parallel execution',
    'PLANNER': 'Three-phase: plan steps → execute → synthesize',
    'COORDINATOR': 'Leader agent delegates via tool calls autonomously',
};
