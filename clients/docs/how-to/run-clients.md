# How-To: Run the Agent Manager Clients

How to start and execute the client apps under [`clients/`](../..). There are two
apps you'll typically run — the **test-client** (headless verifier) and the
**demo-client** (end-user chat UI) — plus the **console** (the full UI test
client) and the shared **`@agm/sdk`** engine they build on.

| App | What it is | Form | Default URL |
|---|---|---|---|
| **test-client** | Exercises/verifies the whole API (scenario harness) | Headless CLI | — (terminal) |
| **demo-client** | End-user product demo (login → chat) | Browser SPA | http://localhost:5174 |
| **console** | Full UI test client (all domains + scenario runner) | Browser SPA | http://localhost:5175 |
| **agm-sdk** | Shared browser+node engine | Library (not run directly) | — |

---

## 0. Prerequisites (shared by all clients)

Everything talks to the **Agent Manager backend on `:8080`**. Start it first.

### a. Start Postgres + Redis (Docker)
```bash
cd /Users/scottesker/Development/Projects/AI/agent-manager
docker compose -f agent-manager/docker/docker-compose.yml up -d
```
(Brings up `agent-manager-db`, `-redis`, `-jaeger`, `-pgadmin`.)

### b. Start the backend (dev profile)
```bash
cd agent-manager
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
- The **dev profile is required** — the default profile fail-fasts on a missing `jwtSecret`.
- Wait for `Started AgentmanagerApplication` (~15s). Verify: `curl -s localhost:8080/v3/api-docs -o /dev/null -w '%{http_code}\n'` → `200`.

### c. A working login (for anything "live")
Use a **dev user whose `org_id` is set** — self-registered users have a null org
and fail agent-scope resolution. The **T2 / LLM** paths additionally need an LLM
**provider key** configured for that org (via the admin UI or
`POST /api/v1/provider-credentials`); without one, agent runs return
`400 "No API key configured…"` — which the clients surface as a WARN, not a crash.

### d. Node
Node 20+ (for `fetch`/`File`/`FormData`). No global installs needed.

---

## 1. test-client (headless verifier)

The test-client lives in the `clients/` **npm workspace** (with `agm-sdk` +
`console`). Install once at the workspace root:

```bash
cd /Users/scottesker/Development/Projects/AI/agent-manager/clients
npm install          # installs agm-sdk + test-client + console
```

### Offline — no backend, no credentials
```bash
cd clients/test-client
npm test                      # vitest — SSE parser, error taxonomy, harness (16 pass / 4 skip)
npm run full -- --dry-run     # list all scenarios + their gating (runs nothing)
npm run full -- --tier=T0     # run only the offline scenarios
```

### Live — needs the backend (§0) + a credential
Set the identity in your shell, then run:
```bash
export AGM_BASE_URL=http://localhost:8080
export AGM_ADMIN_USERNAME=<dev-user>
export AGM_ADMIN_PASSWORD=<password>
export AGM_BUDGET_USD=0.50            # optional: cap T2 (LLM) spend

cd clients/test-client
npm run full                  # everything the environment allows
npm run full -- --tier=T1     # live but no-LLM lanes (tools, creds, discovery, auth)
npm run full -- --domain=F6   # one domain (F1..F12)
npm run full -- --id=TC-TOOL-1,TC-GOV-1
npm run smoke                 # the fixed 8-step E2E
npm run cleanup               # sweep leftover tc-* fixtures (needs admin)
```

Output: each scenario prints ✅ PASS / ❌ FAIL / ⚠️ WARN / ⏭️ SKIP; the process
exits non-zero **only on FAIL**; a JSON report is written to
`clients/test-client/reports/`.

> `tsx`/`vitest` do **not** auto-load `.env`. Either `export` the vars (as above)
> or `set -a; source .env; set +a` first. See [`.env.example`](../../test-client/.env.example)
> for the full var list (multi-identity, budget, flag overrides).

**Optional second org** (unlocks the isolation lanes): also set
`AGM_USER_A_USERNAME/PASSWORD` and `AGM_USER_B_USERNAME/PASSWORD`.

---

## 2. demo-client (end-user chat UI)

A minimal product demo: **login → pick an agent → streaming chat**. It is
**standalone** (its own `package.json` + `node_modules`, not in the workspace),
so install it separately:

```bash
cd /Users/scottesker/Development/Projects/AI/agent-manager/clients/demo-client
npm install          # first time only
npm run dev          # → http://localhost:5174
```

Open **http://localhost:5174**, sign in (§0c), pick an agent, and chat. Token
deltas, tool-call trace, HITL approve/reject, and per-run usage render inline.
The Vite dev server proxies `/api` → `:8080`.

---

## 3. console (full UI test client)

The console is the browser UI that exercises **every** domain — 16 interactive
panels (Agents, Knowledge, Workflows, Teams, Approvals, Memory, Sessions,
Schedules, Jobs, A2A, Tools, Models, Provider keys, Settings, Monitoring,
Evaluations) **plus a Scenario Runner** tab that runs the test-client's scenarios
in-browser. It's a workspace member, so it's already installed by the §1 root
`npm install`.

```bash
cd /Users/scottesker/Development/Projects/AI/agent-manager/clients/console
npm run dev          # → http://localhost:5175
```

Open **http://localhost:5175**, sign in (§0c), and use the left sidebar:
- **Run / Data / Ops / Admin / Observe** — interactive panels (drive APIs by hand, with live streaming + HITL).
- **Test → Scenario Runner** — pick a tier (T0/T1/T2) / domain / budget, click Run; live PASS/FAIL/WARN/SKIP + evidence + JSON download. This is the test-client harness with a face.

---

## Ports at a glance

| Port | App |
|---|---|
| 8080 | Agent Manager backend |
| 5173 | `agent-manager-ui` (the product UI, separate project) |
| 5174 | `clients/demo-client` |
| 5175 | `clients/console` |

The three Vite apps pick distinct ports so they can run side by side.

---

## Which one do I use?

- **Verify the API / CI gate** → **test-client** (`npm run full`).
- **See a product-shaped chat demo** → **demo-client** (:5174).
- **Click through every feature in a browser** (and run the scenarios visually)
  → **console** (:5175).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `ECONNREFUSED` / API calls fail | Backend not up — do §0a + §0b; confirm `:8080/v3/api-docs` → 200. |
| Login fails | Use a dev user with `org_id` set (§0c); self-registered users won't resolve org scope. |
| Agent run → `400 "No API key configured"` | Expected until a provider key is added for the org — not a bug (clients show it as WARN). |
| `@agm/sdk` not found (test-client/console) | Run `npm install` at the **`clients/` root** (workspace), not inside the sub-app. |
| Health panel / probe shows 401 | `/api/v1/health` is secured under the dev profile; 200 in prod via Caddy. Not an error. |
| Port already in use | Another Vite app is running; stop it or change the `server.port` in that app's `vite.config.ts`. |
