# Analysis: A Live UI Console for Exercising Agent Manager APIs

_Analysis of the existing implementation plan ([`agm-test-client-implementation-plan.md`](agm-test-client-implementation-plan.md)) against a new requirement: a **live, browser-based UI** that can run/exercise every Agent Manager API — not just the headless harness. Analysis only; no code._

---

## 1. The ask, and the reframe

The current plan builds a **headless** test client (typed SDK + scenario harness + CLI). The new requirement is a **live UI** — a browser cockpit where a human can log in and drive AGM's features interactively, watch runs stream, resolve HITL, upload KBs, author workflows, and see results.

**This is not a rewrite. It is a second presentation layer over the same engine.** The plan's SDK + scenarios are the reusable core; the CLI (`full.ts`/`smoke.ts`) is one face; a UI is another.

---

## 2. Current state (verified 2026-07-10)

| Asset | Location | Reuse for a UI |
|---|---|---|
| **Comprehensive SDK** (64 methods: auth, agents, runs, sync/bg, sessions, knowledge, workflows, tools, governance, approvals) | `test-client/src/sdk/{http,sse,stream,agm}.ts` | ✅ **as-is** — pure `fetch`/`FormData`/`File` |
| **Scenario harness** (runner, budget, flags, scenario model) | `test-client/src/harness/*` | ✅ browser-portable (runner drives scenarios with a browser-safe `Ctx`) |
| **17 scenarios** (TC-*) + fixtures | `test-client/src/scenarios/*`, `fixtures/*` | ✅ run in-browser unchanged |
| **Node-only bits** | `test-client/src/config.ts` (`process.env`), `harness/report.ts` (`node:fs`) | ❌ **2 files** — replace with a settings form + in-memory/downloadable report |
| **Thin SPA already exists** (login → agent picker → streaming chat) | `demo-client/` (React 19 + Vite + TanStack Query) | ✅ the UI shell + auth + SSE plumbing to build on |
| **Duplicate thin SDK** | `demo-client/src/api/*` | ⚠️ divergent, smaller copy — should be retired in favor of the shared SDK |

**Headline:** ~95% of the engine the UI needs already exists and is browser-safe. The work is a **presentation layer + SDK consolidation**, not new API plumbing.

---

## 3. What "a live UI that runs AGM APIs" should be

Two complementary modes in one app — a **Feature Console**:

### Mode A — Interactive console (drive APIs by hand)
Per-domain panels, each a thin form over an SDK call, with live response rendering:
- **Agents** — list/pick, run with a live **SSE stream view** (token deltas, tool-call trace, reasoning, METRICS/usage), sync vs background toggle.
- **HITL** — when a run PAUSES, an inline **Approve / Reject** control wired to the approvals/escalations endpoints; a pending-approvals inbox.
- **Knowledge/RAG** — create KB, drag-drop upload, poll ingestion, run a search and see scored hits, bind KB→agent and ask a grounded question.
- **Workflows** — author a small DAG (add nodes/edges), validate, run, watch node-run status (and later the `/workflows/ws` live graph).
- **Tools / Governance / Sessions / Observability** — catalog viewer, provider-credential manager, session replay, health/spec panels.

This is the "run the APIs live" the request asks for — a tailored, AGM-aware Postman/Swagger with real streaming + HITL that generic tools can't do.

### Mode B — Scenario runner (the harness, with a face)
A dashboard that runs the **same TC-\* scenarios** in-browser:
- Filter by tier (T0/T1/T2) / domain / id; **Run** button; live PASS/FAIL/WARN/SKIP rows with timing, notes, and expandable **evidence**.
- Because scenarios already carry `{id, tier, prereqs, run(ctx)}`, the UI renders them for free from the registry — no per-scenario UI code.
- Replaces `report.ts`'s file write with an on-screen table + "Download JSON" button.

Mode B is what makes this more than a demo: it's the full-feature test client **with a visual cockpit**, satisfying both "live UI" and "exercise all features."

> **Not worth building:** a raw arbitrary-endpoint firer — springdoc **Swagger UI** already serves that at `/swagger-ui.html` from the live `/v3/api-docs`. The Console's value is the AGM-specific flows (streaming, HITL, RAG grounding, DAG authoring) Swagger can't express.

---

## 4. Recommended architecture

**Extract the browser-safe engine into a shared package; both the CLI and the UI consume it.**

```
clients/
  agm-sdk/            NEW shared package (@agm/sdk) — browser + node safe
    src/sdk/          http, sse, stream, agm  (moved from test-client, unchanged)
    src/scenarios/    TC-* scenarios + fixtures (moved)
    src/harness/      scenario model, runner, budget, flags (moved; report stays out)
    src/types.ts
  test-client/        headless CLI — keeps config.ts, report.ts, full.ts, smoke.ts,
                      cleanup.ts; imports engine from @agm/sdk
  console/            NEW live UI (React 19 + Vite + TanStack Query)
                      imports @agm/sdk; Mode A panels + Mode B runner
  demo-client/        retire its thin SDK → import @agm/sdk (or fold into console)
```

- **Wiring:** an npm **workspace** (root `clients/package.json` with `workspaces`) so `@agm/sdk` resolves by name across apps. Low ceremony, no publish step.
- **Config seam:** the SDK already takes `baseUrl` as a constructor arg and holds the token in memory — the UI supplies these from a **login form + settings panel** instead of `process.env`. `config.ts` stays CLI-only.
- **Report seam:** `report.ts` (fs) stays in the CLI; the UI renders results to the DOM and offers a JSON download. The **runner** and **summarize()** are shared.
- **Streaming/HITL/uploads** already work in the browser (`stream.ts` uses fetch-reader, not `EventSource`; uploads use `FormData`/`File`) — proven by `demo-client`'s existing chat.

**Connectivity (same as demo-client):** browser → **Vite dev proxy** `/api` → `:8080` (no CORS in dev). For a deployed console: either a thin BFF or add its origin to `APP_CORS_ALLOWED_ORIGIN_PATTERNS`. Node is CORS-exempt, which is why the CLI talks to `:8080` directly; the browser needs the proxy/CORS — a real, known difference to plan for.

---

## 5. How the existing plan changes

**The engine plan (Phases 0–5) is unchanged and still correct** — it produces the SDK + scenarios the UI renders. Phase 0 + Phase 1 are already done, so the console can start now against the current engine and grow as later phases land.

Add a parallel **UI track** that rides on the engine:

| UI phase | Delivers | Depends on |
|---|---|---|
| **UI-0 — Scaffold + SDK extraction** | Create `@agm/sdk` workspace pkg (move engine, retire duplicate SDKs); scaffold `console/` (login + settings + shell) reusing demo-client's auth/SSE | engine Phase 0/1 (done) |
| **UI-1 — Interactive console (Mode A)** | Agent-run + live SSE view; HITL approve/reject; per-domain panels for the domains the engine already covers (agents, runs, sessions, tools, knowledge, workflows, governance) | engine Phase 1 (done); grows with 2–5 |
| **UI-2 — Scenario runner (Mode B)** | Render the scenario registry; run/filter; live results + evidence; JSON download | engine runner (done) |
| **UI-3 — Streaming & graph polish** | `/workflows/ws` live graph, activity trace, usage/budget meter, spec-drift badge | engine Phase 3/5 |

Sequencing: **UI-0 → UI-1 → UI-2** delivers a usable live cockpit fast (the domains covered by engine Phases 0–1), then each later engine phase auto-enriches both the CLI and the console (new scenarios appear in Mode B for free; new SDK methods enable new Mode A panels).

---

## 6. Effort & impact

- **UI-0** ~1.5d (mostly the workspace extraction — mechanical file moves + import rewrites; the risk is getting the two Node-only files cleanly excluded).
- **UI-1** ~3–4d (panels are thin forms over existing SDK methods; the streaming/HITL view is the one substantive component, and demo-client already has a working version to adapt).
- **UI-2** ~1.5d (the registry renders itself; the runner already exists — this is a results table + a Run button).
- **UI-3** ~2–3d (WS graph is the biggest new piece).

Net: a genuinely useful **live API console in ~1 week**, because it's presentation over a proven engine, not new plumbing.

---

## 7. Decisions for the user

1. **Console home** — new `clients/console/` app (clean separation) **[recommended]**, or grow `demo-client` into it (fewer apps, but conflates "demo" and "console")?
2. **SDK sharing** — extract `@agm/sdk` workspace package **[recommended]** vs. keep per-app copies (fast now, drift later — already happening between demo/test).
3. **Scope of v1** — Mode A (interactive) + Mode B (scenario runner) together **[recommended]**, or interactive-only first?
4. **Deploy target** — dev-proxy only for now **[recommended]**, or plan a deployed console (BFF/CORS) from the start?

---

## 8. Recommendation

Build a **`clients/console/` Feature Console** (React 19 + Vite + TanStack Query) that consumes a new **`@agm/sdk`** workspace package (the extracted, browser-safe engine), delivering **Mode A interactive panels + Mode B scenario runner**. Reuse demo-client's auth/SSE shell; talk to `:8080` via the Vite proxy in dev. The headless CLI keeps working off the same `@agm/sdk`; the two Node-only files stay CLI-side. This turns the headless test client into a **live, visual cockpit for running and verifying every AGM API** with ~95% engine reuse and one week of presentation work — while the engine plan (Phases 2–5) continues to enrich both faces.
