const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const BASE_URL = 'http://localhost:5174';
const OUT_DIR = '/Users/scottesker/Development/Projects/AI/agent-manager/website/assets/screenshots';

const FAKE_TOKEN = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIiwidXNlcm5hbWUiOiJzZXNrZXIiLCJyb2xlcyI6WyJhZG1pbiJdLCJpYXQiOjE3MTM0MDA0MjIsImV4cCI6OTk5OTk5OTk5OX0.placeholder';
const FAKE_USER = JSON.stringify({ id: '1', username: 'demo-admin', email: 'demo-admin@example.com', roles: ['admin'] });

const PAGES = [
  { route: '/',              file: 'dashboard.png' },
  { route: '/agents',        file: 'agents.png' },
  { route: '/sessions',      file: 'sessions.png' },
  { route: '/workflows',     file: 'workflows.png' },
  { route: '/approvals',     file: 'approvals.png' },
  { route: '/schedules',     file: 'schedules.png' },
  { route: '/memory',        file: 'memory.png' },
  { route: '/registry',      file: 'registry.png' },
  { route: '/mcp',           file: 'mcp-servers.png' },
  { route: '/security',      file: 'security.png' },
  { route: '/observability', file: 'observability.png' },
  { route: '/finops',        file: 'finops.png' },
  { route: '/a2a',           file: 'network-mesh.png' },
  { route: '/settings',      file: 'settings.png' },
];

fs.mkdirSync(OUT_DIR, { recursive: true });

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
  });

  // Inject auth into localStorage before any page load
  await context.addInitScript(([token, user]) => {
    localStorage.setItem('auth_token', token);
    localStorage.setItem('auth_user', user);
  }, [FAKE_TOKEN, FAKE_USER]);

  const page = await context.newPage();

  for (const { route, file } of PAGES) {
    const url = BASE_URL + route;
    console.log(`Screenshotting ${url} → ${file}`);
    await page.goto(url, { waitUntil: 'networkidle', timeout: 15000 });
    await page.waitForTimeout(800); // let any animations settle
    const outPath = path.join(OUT_DIR, file);
    await page.screenshot({ path: outPath, fullPage: false });
    console.log(`  ✓ saved ${outPath}`);
  }

  await browser.close();
  console.log('\nAll screenshots saved to', OUT_DIR);
})();
