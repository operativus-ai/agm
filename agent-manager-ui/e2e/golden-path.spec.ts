import { test, expect } from '@playwright/test';

/**
 * UI-level golden-path smoke test. Companion to the backend
 * `GoldenPathSmokeRuntimeTest` — same shape (register → login → see empty
 * state) but driven through a real browser, hitting the FE + backend
 * end-to-end. This catches FE-only regressions the backend smokes can't see:
 *
 *   - Broken /login form (router miswire, missing input fields, broken submit)
 *   - Broken /register form (validation rules out of sync with backend)
 *   - Broken auth-context state (login redirect doesn't fire, AuthGuard
 *     evicts authenticated users back to /login)
 *   - Broken empty-state rendering on /agents (page errors out when there
 *     are zero agents)
 *
 * Each test generates a fresh username so the spec can run repeatedly against
 * the same target without cleanup. No fixture teardown — the rows persist
 * on the target deployment, which is acceptable for a smoke probe.
 *
 * Target URL comes from BASE_URL env var (see playwright.config.ts). Default
 * is the Vite dev server (http://localhost:5173). For staging/prod smoke:
 *   BASE_URL=https://staging.app.example.com npm run e2e
 */

function uniqueUsername(prefix: string): string {
	// Username constraint: alphanumeric + dashes/underscores, short enough to
	// survive backend validation. Timestamp + random suffix yields collision-free
	// names across rapid repeat runs.
	const suffix = `${Date.now()}-${Math.floor(Math.random() * 1000)}`;
	return `${prefix}-${suffix}`;
}

test.describe('Golden path — UI smoke', () => {
	test('register → login → land on /agents with empty state', async ({ page }) => {
		const username = uniqueUsername('smoke');
		const email = `${username}@e2e.test`;
		const password = 'e2e-smoke-pass-1234';

		// ── 1. Visit /register and fill the form ───────────────────────────────
		// The page uses placeholder-based form fields (no semantic name/id),
		// so we locate by placeholder. If the form is refactored to use labels
		// or test-ids, this locator strategy needs to follow.
		await page.goto('/register');
		await expect(page).toHaveURL(/\/register$/);

		await page.getByPlaceholder('username').fill(username);
		await page.getByPlaceholder('email@example.com').fill(email);
		await page.getByPlaceholder('password').fill(password);

		// Submit. The register flow returns 200 from /api/auth/register but does
		// NOT auto-login on the backend — the FE follow-up is to redirect the
		// user to /login (or to auto-submit a login). The smoke covers both
		// possibilities by tolerating either landing.
		await Promise.all([
			page.waitForURL(/\/(login|register|dashboard|agents)/, { timeout: 15_000 }),
			page.getByRole('button', { name: /register|sign\s*up|create/i }).click(),
		]);

		// ── 2. If we ended up on /login (not auto-logged-in), log in. ─────────
		if (page.url().endsWith('/login') || page.url().endsWith('/register')) {
			await page.goto('/login');
			await page.getByPlaceholder('username').fill(username);
			await page.getByPlaceholder('password').fill(password);
			await Promise.all([
				page.waitForURL(/\/(dashboard|agents|$)/, { timeout: 15_000 }),
				page.getByRole('button', { name: /login|sign\s*in|log\s*in/i }).click(),
			]);
		}

		// ── 3. Sanity: we're inside the authenticated app. ─────────────────────
		// AuthGuard would have bounced us back to /login if the JWT didn't take.
		// Reaching any /dashboard or /agents route is the proof.
		expect(page.url()).toMatch(/\/(dashboard|agents|$)/);

		// ── 4. Navigate to /agents and verify it renders without erroring. ────
		// New tenants have zero agents. The page should render the empty state
		// (some friendly "create your first agent" message) WITHOUT a stack
		// trace, ErrorBoundary fallback, or "Something went wrong" toast.
		await page.goto('/agents');
		await expect(page).toHaveURL(/\/agents$/);

		// Hard signal: no rendered error UI. We don't pin the exact empty-state
		// text (it may evolve) — only that nothing on the page reads as a crash.
		const errorBanner = page.getByText(/something went wrong|unexpected error|error boundary/i);
		await expect(errorBanner).toHaveCount(0);

		// Soft signal: the page actually rendered some content (not a blank).
		// Vite serves a blank HTML shell if the SPA fails to bootstrap.
		const body = await page.locator('body').innerText();
		expect(body.trim().length).toBeGreaterThan(0);
	});
});
