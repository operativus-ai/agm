import { type Page, expect } from '@playwright/test';

/**
 * Shared auth helper for e2e specs. Mirrors the register → (fallback) login
 * flow proven in golden-path.spec.ts: placeholder-based forms, a fresh
 * collision-free username per run, and tolerance for either auto-login or a
 * redirect to /login after register.
 */

export function uniqueUsername(prefix: string): string {
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 1000)}`;
  return `${prefix}-${suffix}`;
}

/**
 * Register a fresh user and land authenticated inside the app. Resolves once
 * an authenticated route (/dashboard, /agents, or /) is reached; throws via
 * the final assertion if AuthGuard bounced us back to /login.
 */
export async function registerAndLogin(page: Page, prefix = 'visual'): Promise<void> {
  const username = uniqueUsername(prefix);
  const email = `${username}@e2e.test`;
  const password = 'e2e-visual-pass-1234';

  await page.goto('/register');
  await page.getByPlaceholder('username').fill(username);
  await page.getByPlaceholder('email@example.com').fill(email);
  await page.getByPlaceholder('password').fill(password);
  // Wait for the register flow to actually leave /register before logging in.
  // Otherwise we race the register POST and try to log in before the user row
  // exists — the main flake source on a cold/redis-down backend. 30s timeouts
  // absorb cold-start latency (JWT filter chain + cache-miss fallbacks).
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/register'), { timeout: 30_000 }),
    page.getByRole('button', { name: /register|sign\s*up|create/i }).click(),
  ]);

  // If we landed on /login (register doesn't auto-login), complete the login.
  if (page.url().endsWith('/login')) {
    await page.goto('/login');
    await page.getByPlaceholder('username').fill(username);
    await page.getByPlaceholder('password').fill(password);
    await Promise.all([
      page.waitForURL(/\/(dashboard|agents|$)/, { timeout: 30_000 }),
      page.getByRole('button', { name: /login|sign\s*in|log\s*in/i }).click(),
    ]);
  }

  expect(page.url()).toMatch(/\/(dashboard|agents|$)/);
}
