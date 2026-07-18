import { test, expect } from '@playwright/test';
import { registerAndLogin } from './helpers/auth';

/**
 * Visual-regression baselines for the FE UI refactor (DaisyUI -> shadcn/ui).
 *
 * Purpose: lock in before/after screenshots of the card-heavy pages so the
 * component-library swap can be verified pixel-for-pixel. This is the Phase 1
 * prerequisite called out in docs/analysis/agm-fe-ui-refactor-plan-shadcn.md.
 *
 * OPT-IN: the whole suite is skipped unless VISUAL=1, so it never breaks the
 * default `npm run e2e` smoke gate (which commits no baselines for it). It also
 * needs a running stack (FE + backend) to authenticate and render real pages.
 *
 * Workflow:
 *   # 1. On main (pre-refactor) — capture the baselines:
 *   VISUAL=1 BASE_URL=http://localhost:5173 npx playwright test visual-regression --update-snapshots
 *   # 2. After a shadcn swap — diff against them:
 *   VISUAL=1 BASE_URL=http://localhost:5173 npx playwright test visual-regression
 *
 * Baselines land in e2e/visual-regression.spec.ts-snapshots/ and SHOULD be
 * committed so the diff is reviewable in the PR.
 */

// Card-heavy routes most affected by the DaisyUI -> shadcn swap. Keep the set
// focused — every entry is a committed baseline image to maintain.
const ROUTES: Array<{ path: string; name: string }> = [
  { path: '/',              name: 'dashboard' },
  { path: '/agents',        name: 'agents' },
  { path: '/chat',          name: 'chat-picker' },
  { path: '/settings',      name: 'settings' },
  { path: '/finops',        name: 'finops' },
  { path: '/evaluations',   name: 'evaluations' },
  { path: '/memory',        name: 'memory' },
  { path: '/observability', name: 'observability' },
];

test.describe('Visual regression — card-heavy pages', () => {
  test.skip(!process.env.VISUAL, 'set VISUAL=1 to run visual-regression checks (needs a running stack + committed baselines)');

  // Fresh authenticated session per test (stateless, like golden-path).
  test.beforeEach(async ({ page }) => {
    // Auth (register+login) + page data load can be slow on a cold backend, so
    // give each test headroom beyond the global 30s default.
    test.setTimeout(90_000);
    await registerAndLogin(page);
  });

  for (const route of ROUTES) {
    test(`screenshot ${route.name}`, async ({ page }) => {
      await page.goto(route.path);
      // Deterministic settle instead of networkidle: several pages poll on a
      // timer (dashboard, ActiveRunsTracker), so networkidle can never quiesce.
      // Wait for load + a fixed delay so async data has rendered.
      await page.waitForLoadState('load');
      await page.waitForTimeout(2_000);
      await expect(page).toHaveScreenshot(`${route.name}.png`, { fullPage: true });
    });
  }
});
