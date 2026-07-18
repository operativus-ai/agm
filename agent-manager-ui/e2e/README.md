# Agent Manager UI — End-to-end Smoke Tests

Browser-driven smoke tests exercising the FE + backend together. Companion to the backend `*GoldenPathSmoke*` suite — same shape, different layer.

Runs via [Playwright](https://playwright.dev). The current spec exercises the golden journey: **register → login → land on `/agents`** without a render error.

## What it catches that backend tests can't

- Broken `/login` or `/register` form (router miswire, missing fields, broken submit handler)
- Validation rules out of sync between FE form and backend DTO
- Broken auth-context state (login redirect doesn't fire, `AuthGuard` evicts an authenticated user)
- Broken empty-state rendering on `/agents` (page errors out when there are zero agents)
- Tailwind / DaisyUI / theme-token regressions that produce a visually-broken but technically-rendering page

## What it does NOT cover

- Agent CRUD or run flows (would need backend `FakeChatModel` + model row seeding; runs against arbitrary live targets without that fixture surface). Backend `GoldenPathSmokeRuntimeTest` already covers the API-level happy path including run dispatch.
- Cross-browser (chromium only)

> Visual regressions are covered by a separate **opt-in** suite — see
> [Visual regression](#visual-regression-opt-in) below.

## Running locally

```bash
# 1. Bring up the stack the smoke targets. Either:
#    a) Dev: backend on :8080 + Vite dev server on :5173
#       cd agent-manager   && ./mvnw spring-boot:run
#       cd agent-manager-ui && npm run dev
#    b) Or prod-like via the compose stack (Caddy on :80 reverse-proxying everything):
#       docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --build

# 2. Install Playwright + the chromium browser the first time
cd agent-manager-ui
npm install
npm run e2e:install         # ~150 MB of browser, once per machine

# 3. Run against the chosen target
BASE_URL=http://localhost:5173 npm run e2e        # default: Vite dev
BASE_URL=http://localhost     npm run e2e          # local prod compose
BASE_URL=https://staging.app.example.com npm run e2e   # staging smoke
BASE_URL=https://app.example.com npm run e2e            # prod smoke
```

`BASE_URL` defaults to `http://localhost:5173` so a developer who already has Vite running gets a working `npm run e2e` invocation.

## Running headed / debug

```bash
npm run e2e:headed          # see the browser steer through the journey
npm run e2e:debug           # Playwright Inspector — step through with a debugger
```

## Visual regression (opt-in)

`visual-regression.spec.ts` captures full-page screenshots of the card-heavy
pages so the **DaisyUI → shadcn/ui** component swap (see
`docs/analysis/agm-fe-ui-refactor-plan-shadcn.md`) can be verified
pixel-for-pixel. It is **skipped unless `VISUAL=1`**, so it never affects the
default `npm run e2e` smoke gate, and it needs a running stack to authenticate.

```bash
# 1. On main (pre-refactor) — capture + commit the baselines:
VISUAL=1 BASE_URL=http://localhost:5173 npx playwright test visual-regression --update-snapshots
git add e2e/visual-regression.spec.ts-snapshots && git commit -m "test(ui): visual baselines (pre-shadcn)"

# 2. After a shadcn swap — diff against the committed baselines:
VISUAL=1 BASE_URL=http://localhost:5173 npx playwright test visual-regression

# Intentional visual change? Re-baseline and commit the new images in the same PR:
VISUAL=1 npx playwright test visual-regression --update-snapshots
```

Baselines live in `e2e/visual-regression.spec.ts-snapshots/` and **must be
committed** for the diff to be reviewable. Tolerances (`maxDiffPixelRatio`,
`animations: 'disabled'`) are set in `playwright.config.ts`.

> Suggested `package.json` scripts (add when convenient):
> `"e2e:visual": "VISUAL=1 playwright test visual-regression"` and
> `"e2e:visual:update": "VISUAL=1 playwright test visual-regression --update-snapshots"`.

## CI shape (future)

The spec is CI-friendly: `retries=0` under `CI=true`, JUnit-xml reporter writes `playwright-junit.xml` for the CI dashboard. To wire as a job in `.github/workflows/ci.yml`:

```yaml
ui-smoke:
  name: UI smoke (Playwright)
  runs-on: ubuntu-latest
  needs: [smoke]
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-node@v4
      with: { node-version: 24, cache: npm, cache-dependency-path: agent-manager-ui/package-lock.json }
    - working-directory: agent-manager-ui
      run: |
        npm ci
        npx playwright install --with-deps chromium
        # Bring up the stack here, or point BASE_URL at a deploy preview
        BASE_URL=${{ secrets.STAGING_URL }} npx playwright test
```

Not wired by default — the test needs a running target. Add when you have a staging URL or a docker-in-docker workflow that brings the compose stack up inside CI.
