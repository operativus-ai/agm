import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the Agent Manager UI smoke tests.
 *
 * Target URL is env-driven so the same spec runs against:
 *   - local dev:        BASE_URL=http://localhost:5173  (Vite dev server)
 *   - local prod stack: BASE_URL=http://localhost       (Caddy on port 80)
 *   - staging:          BASE_URL=https://staging.app.example.com
 *   - production smoke: BASE_URL=https://app.example.com
 *
 * Default is the Vite dev port to make `npm run e2e` work out-of-the-box for
 * a developer who has the dev server already running on the side.
 */
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:5173';

export default defineConfig({
	testDir: './e2e',

	// Spec timeout — generous because some pages do server-side calls that bring
	// up the JWT filter chain on cold first-request after deploy.
	timeout: 30_000,

	// Visual-regression tolerances for expect(page).toHaveScreenshot() in
	// visual-regression.spec.ts (opt-in via VISUAL=1). animations disabled so
	// transitions don't make snapshots flaky; a small pixel-ratio threshold
	// absorbs AA/subpixel noise while still tripping on real layout/color drift.
	expect: {
		toHaveScreenshot: {
			maxDiffPixelRatio: 0.02,
			animations: 'disabled',
		},
	},

	// In CI we want hard failure on retries — flakes need to be fixed, not
	// papered over. Locally a single retry covers occasional dev-server hiccups.
	retries: process.env.CI ? 0 : 1,

	// One worker keeps the smoke deterministic. The journey serially registers +
	// logs in + interacts; running in parallel would race state.
	workers: 1,

	reporter: process.env.CI ? [['list'], ['junit', { outputFile: 'playwright-junit.xml' }]]
		: [['list']],

	use: {
		baseURL: BASE_URL,
		// Capture artifacts only on failure to keep successful runs fast.
		screenshot: 'only-on-failure',
		video: 'retain-on-failure',
		trace: 'on-first-retry',
		// Don't disable the cert check by default — production-like targets
		// MUST have a valid cert (catches the "Caddy didn't get a cert" case
		// before users do).
		ignoreHTTPSErrors: false,
	},

	projects: [
		{
			name: 'chromium',
			use: { ...devices['Desktop Chrome'] },
		},
	],
});
