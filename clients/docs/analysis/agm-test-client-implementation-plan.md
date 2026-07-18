# Implementation Plan: Full-Feature Test Client

_Detailed build plan for the 58 requirements in [`agm-test-client-requirements.md`](agm-test-client-requirements.md). Extends the existing skeleton at [`clients/test-client/`](../../test-client). Plan only — nothing here is built yet._

Endpoint roots below are **verified** against the backend `@RequestMapping` annotations (2026-07-10), so the SDK modules can be scaffolded against real paths.

---

## 0. Guiding decisions (locked)

1. **Extend `clients/test-client`, do not fork.** The proven core (`sdk/http.ts` error taxonomy, `sdk/sse.ts` pure parser, `sdk/stream.ts` `collectRun`, `sdk/agm.ts` facade, reporter, config) is the foundation. Every phase adds modules; nothing in the skeleton is rewritten.
2. **Scenario, not script.** Each requirement becomes a `Scenario` object — `{ id, domain, tier, priority, prereqs, run(ctx), cleanup(ctx) }` — discovered by a runner. Uniform reporting, filtering (`--tier`, `--domain`, `--id`), and CI wrapping come free.
3. **Tier discipline (NFR-1).** T0 offline / T1 live-no-LLM / T2 live-LLM stamped on every scenario. The runner refuses to run a tier whose prereqs are absent (WARN/SKIP, never FAIL).
4. **Fixtures self-provision + prefix-clean (NFR-3).** Everything created is tagged `tc-<runId>-…`; a `cleanup` command sweeps by prefix. No dependence on seed data beyond the bootstrap admin.
5. **Zero runtime deps stays (NFR-8).** Node-native `fetch` / `WebSocket` / `node:http`. `tsx` + `vitest` remain dev-only.

---

## 1. Verified endpoint map (SDK module targets)

| SDK module | Controllers (root path) |
|---|---|
| `auth.ts` (exists) | AuthController `/api/auth` · UserAdminController `/api/admin/users` |
| `agents.ts` (exists→grow) | AgentsController `/api/agents` · AgentAdminController `/api/admin/agents` · RegistryController `/api/v1/registry` |
| `runs.ts` | RunsController / RunTelemetryController / RunEventSseController / SseTokenController `/api/v1/runs` · AgentEventSse `/api/v1/agents` · OrgEventSse `/api/v1/observability` |
| `sessions.ts` (exists→grow) | SessionController `/api/sessions` |
| `memory.ts` | MemoryController `/api/memories` · AgenticMemoryController `/api/v1/memories` · MemoryTaggingController `/api/memories` · AgentReflectionController `/api/v1/agents` |
| `approvals.ts` (exists→grow) | ApprovalsController `/api/v1/approvals` · EscalationsController `/api/v1/escalations` |
| `knowledge.ts` | KnowledgeBaseController `/api/v1/knowledge-bases` · KnowledgeController / KnowledgePreviewController `/api/knowledge` · EmbeddingBackfillAdminController `/api/v1/admin/embeddings` |
| `workflows.ts` | WorkflowsController `/api/v1/workflows` (+ `/workflows/ws` WebSocket) |
| `teams.ts` | TeamsController `/api/v1/teams` · aggregates `/api/v1/observability/aggregates` |
| `tools.ts` | ToolController `/api/tools` · ExtensionController `/api/v1/extensions` · Composio `/api/admin/composio/*` |
| `mcp.ts` | McpController `/mcp` · McpLifecycleController `/api/mcp` |
| `scheduling.ts` | SchedulesController `/api/v1/schedules` · JobsController `/api/jobs` · BackgroundJobController `/api/v1/observability/background-jobs` |
| `a2a.ts` | A2AController `/api/v1/a2a` · A2aHealthController `/api/v1/health` |
| `governance.ts` | PiiAdminController `/api/v1/pii-policies` · ProviderCredentialAdminController `/api/v1/provider-credentials` · ModelController `/api/models` · ModelCatalogController `/api/v1/models/catalog` · SettingsController `/api/v1/settings` · ConfigController `/api/config` · FinOpsAdminController `/api/v1/finops` · BudgetExceededController `/api/observability` · DataRetentionController `/api/admin/retention` · ComplianceController `/api/compliance` · AuditLogController `/api/admin/audit-logs` · SystemAuditLogController `/api/admin/system-audit-logs` · IncidentResponseController `/api/v1/admin` |
| `observability.ts` | MonitoringController `/api/monitoring` · ObservabilityController / SloController `/api/v1/observability` · EvaluationController `/api/v1/evaluations` · `/v3/api-docs` |

> Flagged (opt-in lanes): SkillAdminController `/api/v1/skills` (`agm.skills.enabled`) · OrgRoutingConfigAdminController `/api/v1/routing-config` + UniversalDispatchController `/api/runs` (`agm.universal-dispatch.enabled`).

---

## 2. Target project structure (end state)

```
clients/test-client/
  src/
    types.ts                    grows: per-domain wire types (or split into types/*.ts)
    config.ts                   grows: identities (admin/userA/userB), flag detection, budget ceiling
    sdk/
      http.ts        (exists)   + retry/backoff for 202-async, multipart POST helper
      sse.ts         (exists)   unchanged (pure parser)
      stream.ts      (exists)   + activePort/node-run assertions for workflow runs
      ws.ts          (NEW)      WebSocket client for /workflows/ws (node-state frames)
      agm.ts         (exists)   thin aggregator re-exporting the domain modules below
      auth.ts agents.ts runs.ts sessions.ts memory.ts approvals.ts
      knowledge.ts workflows.ts teams.ts tools.ts mcp.ts scheduling.ts
      a2a.ts governance.ts observability.ts        (NEW, one per §1 row)
    harness/
      scenario.ts    (NEW)      Scenario type + Ctx (clients, fixtures, logger, budget)
      runner.ts      (NEW)      discovery, tier/prereq gating, filtering, ordering
      report.ts      (NEW)      JSON artifact + console table (NFR-6)
      budget.ts      (NEW)      token/cost accumulator + ceiling abort (NFR-7)
      callback-server.ts (NEW)  node:http listener for webhook nodes + A2A peer stub
    fixtures/
      identities.ts  (NEW)      provision admin/userA/userB + orgs via UserAdminController
      agent-fixture.ts kb-fixture.ts workflow-fixture.ts team-fixture.ts (NEW)
      cleanup.ts     (NEW)      sweep tc-<runId>-* fixtures
    scenarios/
      f1-auth.ts f2-agents.ts f3-runs.ts f4-sessions-memory.ts f5-hitl.ts
      f6-rag.ts f7-workflows.ts f8-teams.ts f9-tools.ts f10-scheduling-a2a.ts
      f11-governance.ts f12-observability.ts       (NEW)
    smoke.ts         (exists)   unchanged — the 2-min core-contract pass
    full.ts          (NEW)      tiered full-feature entrypoint (npm run full -- --tier=T1)
  tests/
    *.test.ts        (exists)   + vitest wrappers that import scenarios and run them in CI mode
```

New npm scripts: `full` (`tsx src/full.ts`), `cleanup` (`tsx -e` sweep), `report` (re-render last JSON).

---

## 3. Phased delivery

Six phases. Each ends **green + committed** (own PR, stacked on predecessor, per the project's PR workflow). Effort is rough dev-days for one engineer.

### Phase 0 — Harness foundation (no new coverage) · ~2d
Build the machinery every later phase depends on. **No feature scenarios yet** — proves the runner on the existing 8 smoke checks re-expressed as scenarios.
- `harness/scenario.ts`, `runner.ts`, `report.ts`, `budget.ts`.
- `fixtures/identities.ts` (+ orgs), `fixtures/cleanup.ts`, prefix convention.
- `config.ts` → multi-identity + `detectFlags()` (reads `ConfigController`/settings to learn which flag lanes are live).
- `full.ts` entrypoint with `--tier/--domain/--id/--dry-run` filters.
- Port the 8 smoke steps into `scenarios/` as the first scenarios; `smoke.ts` stays as the fast path.
- **Exit criteria:** `npm run full -- --dry-run` lists all discovered scenarios with tier/prereq; the ported smoke set runs and reports identically to today.

### Phase 1 — P1 breadth (every domain touched once) · ~4d
The 12 P1 requirements — a "deep smoke" spanning all domains. SDK modules created lazily as each scenario needs them (thin — only the calls the P1 scenario uses).
- Scenarios: TC-AUTH-1/2, AGENT-1, RUN-1/2, SESS-1/2, HITL-1, RAG-1, WF-1, TOOL-1, GOV-1, OBS-1.
- SDK modules touched: auth, agents, runs, sessions, approvals, knowledge (upload+search+poll), workflows (create/validate/run), tools, governance (provider-cred add/remove), observability (health + api-docs).
- `fixtures/agent-fixture.ts` + `kb-fixture.ts` + `workflow-fixture.ts` (linear only).
- **Exit criteria:** all P1 pass at T1/T2 against a booted BE with one provider key; the whole set self-provisions and cleans up; provider-key absence degrades RAG/RUN scenarios to WARN.

### Phase 2 — Streaming & HITL depth · ~4d
The event-grammar and approval-flow depth that is AGM's signature.
- **runs.ts:** SSE event feeds (TC-RUN-4) — `SseTokenController` mint → subscribe run/agent/org streams; usage reconciliation (TC-RUN-3) METRICS vs telemetry; concurrency-cap 400 (TC-RUN-5); media lanes (TC-RUN-6); follow-ups/reasoning (TC-RUN-7).
- **approvals.ts:** inbox pending+resolve+idempotency (TC-HITL-2); escalation path (TC-HITL-3); FinOps gate flag lane (TC-HITL-4); SLA/timeout (TC-HITL-5).
- **memory.ts:** agentic memory persistence across sessions (TC-MEM-1), tagging/reflections (TC-MEM-2); sessions CRUD depth (TC-SESS-3).
- **Exit criteria:** the run event grammar and full HITL lifecycle are assertable on the wire; FinOps-gate lane runs only when the flag is detected live.

### Phase 3 — Workflows & teams · ~5d
The orchestration engines — highest new-surface-area.
- **ws.ts** WebSocket client + **harness/callback-server.ts** (node:http) for webhook nodes.
- **workflows.ts:** node-kind matrix (TC-WF-2, all 9 kinds — one scenario each), fail-closed condition (TC-WF-3), pause/resume frontier (TC-WF-4), live-viewer WS feed (TC-WF-5), retry/timeout + cycle-rejection (TC-WF-6). `workflow-fixture.ts` grows a small DAG builder (nodes+edges+ports).
- **teams.ts:** CRUD+members+sequential (TC-TEAM-1), strategy matrix Router/Planner/Swarm (TC-TEAM-2), delegation topology + depth cap (TC-TEAM-3), health/spend aggregation (TC-TEAM-4).
- **Exit criteria:** every workflow node kind and every team strategy exercised once; the callback server proves webhook + is reused by Phase 4's A2A peer.

### Phase 4 — Integrations & scheduling · ~4d
- **mcp.ts:** AGM-as-MCP-server — connect over `/mcp` SSE + `/api/mcp` lifecycle, list+invoke a tool (TC-TOOL-2).
- **tools.ts:** extensions pre/post hook (TC-TOOL-4), Firecrawl-down degradation + SSRF refuse (TC-TOOL-5), Composio gated (TC-TOOL-3).
- **scheduling.ts:** one-shot + cron lifecycle (TC-SCHED-1/2), job queue enqueue→poll→terminal (TC-JOB-1).
- **a2a.ts + callback-server.ts:** register the harness as an A2A peer, receive a dispatched task, answer, assert completion + mesh health (TC-A2A-1).
- **Exit criteria:** the harness acts as both MCP client and A2A peer; scheduled + queued work observed end-to-end.

### Phase 5 — Governance, isolation & observability · ~5d
The admin/safety/multi-tenant depth (needs the 2nd org from Phase 0).
- **governance.ts:** PII redaction+audit (TC-GOV-2), injection guard (TC-GOV-3), FinOps cap burn+reset (TC-GOV-4), models/settings CRUD (TC-GOV-5), audit trails + immutability (TC-GOV-6), compliance/retention/erasure (TC-GOV-7), provider-key transition assertion (TC-GOV-1 depth).
- **Isolation suite (client-side IDOR):** TC-RAG-4 + TC-GOV-8 — org-B token against org-A ids across agents/sessions/KB/PII-audit → 404/403/empty everywhere. Client-side complement to the server `tenant-authz-auditor`.
- **observability.ts:** **OpenAPI spec-drift gate (TC-OBS-2)** — diff live `/v3/api-docs` against the SDK's endpoint registry; telemetry deltas (TC-OBS-3); evaluations create→execute→score (TC-OBS-4); role-matrix authz mirror (TC-AUTH-6).
- Remaining P3 edges: agent export/import + versions (TC-AGENT-3/4), RAG preview/dedup/cascade/backfill + reranker lane (TC-RAG-5/6), password-reset (TC-AUTH-5, env-gated).
- **Exit criteria:** full 58-requirement matrix implemented; spec-drift gate green against current main; isolation suite proves tenancy on the wire.

**Total: ~24 dev-days** across 6 phases (P0 foundation + 5 coverage phases).

---

## 4. Requirement → phase traceability

| Phase | Requirements | Count |
|---|---|---|
| 0 | (foundation — re-expresses the 8 smoke checks) | — |
| 1 | AUTH-1/2, AGENT-1, RUN-1/2, SESS-1/2, HITL-1, RAG-1, WF-1, TOOL-1, GOV-1, OBS-1 | 12 (all P1) |
| 2 | RUN-3/4/5/6/7, HITL-2/3/4/5, MEM-1/2, SESS-3 | 12 |
| 3 | WF-2/3/4/5/6, TEAM-1/2/3/4 | 9 |
| 4 | TOOL-2/3/4/5, SCHED-1/2, JOB-1, A2A-1 | 8 |
| 5 | GOV-2/3/4/5/6/7/8, RAG-2/3/4/5/6, OBS-2/3/4, AUTH-5/6, AGENT-2/3/4 | 17 |

= 58 across phases 1–5 (12 P1 · 27 P2 · 19 P3), matching the requirements doc's traceability count.

---

## 5. Cross-cutting components (build once, used everywhere)

| Component | Phase | Purpose |
|---|---|---|
| `harness/scenario.ts` + `runner.ts` | 0 | scenario model, discovery, tier/prereq gating, filtering |
| `harness/report.ts` | 0 | JSON artifact + PASS/FAIL/WARN/SKIP console table (NFR-6) |
| `harness/budget.ts` | 0 | METRICS-frame accumulator + ceiling abort (NFR-7) |
| `fixtures/identities.ts` + `cleanup.ts` | 0 | admin/userA/userB + orgs; prefix sweep (NFR-3/5) |
| `config.detectFlags()` | 0 | learn live flag lanes → auto-skip inactive (skills, universal-dispatch, finops-gate, reranker) |
| `harness/callback-server.ts` | 3 | node:http listener — webhook node target **and** A2A peer stub |
| `sdk/ws.ts` | 3 | `/workflows/ws` live-viewer frames |
| OpenAPI drift checker | 5 | live `/v3/api-docs` vs SDK endpoint registry (TC-OBS-2) |

---

## 6. Testing & CI integration

- **Every scenario is also a vitest test.** `tests/*.test.ts` import scenarios and run them under `describe.skipIf(!tierAvailable)`, so `npm test` = T0 always + higher tiers when env present. Keeps one source of truth (no duplicate assertions between smoke CLI and vitest).
- **CI lanes:**
  - **T0** — runs in the existing `ci.yml` unit job (no BE). Gates every PR to `clients/test-client`.
  - **T1** — a new optional job that boots prod-compose (or points at a dev URL) and runs `--tier=T1`; the missing "smoke the real deployment" step for the CI→GHCR→deploy pipeline.
  - **T2** — nightly / manual dispatch with a provider key secret + budget ceiling.
- **Artifact:** `report.ts` writes `report-<runId>.json`; CI uploads it. Doubles as the pre-release deployment-gate evidence.

---

## 7. Risks & mitigations

| Risk | Mitigation |
|---|---|
| **T2 non-determinism** (LLM prose varies) | semantic-tolerant assertions only (contains-seeded-fact); never exact-match. Enforced by convention + a lint note in scenario authoring. |
| **Fixture leakage** on crash mid-run | prefix everything `tc-<runId>-`; `cleanup` command + a `finally` sweep in the runner; caps/budget scenarios restore prior values in `finally`. |
| **Budget burn** by a looping run | `budget.ts` hard ceiling aborts the set; T2 scenarios declare per-scenario token caps. |
| **Callback reachability** (webhook/A2A) | dev/same-host only in v1; document that a deployed BE needs a reachable harness URL (skip lane otherwise). |
| **Flag-lane drift** (a flag renamed) | `detectFlags()` reads live config, not hardcoded assumptions; unknown flag → lane SKIP with a note. |
| **Endpoint drift** during the ~24d build | TC-OBS-2 spec-drift gate (Phase 5) becomes self-protecting; until then, the verified §1 map is the reference. |
| **Auth model edge** (null-org self-user) | encoded as an explicit assertion (TC-AUTH-3), not an incidental failure. |
| **Scope creep into load testing** | out-of-scope per requirements §7; concurrency-cap probe is a single contract assertion, not a load lane. |

---

## 8. Definition of done

- All 58 requirements implemented as scenarios; each stamped tier + priority.
- `npm test` green offline (T0); `npm run full -- --tier=T1` green against prod-compose with one provider key; T2 green nightly under budget.
- Fixtures fully self-provision + clean; back-to-back re-runs stable (NFR-4).
- JSON report artifact produced; T0 wired into `ci.yml`; T1 available as an optional deploy-gate job.
- Spec-drift gate (TC-OBS-2) green against current `main`.
- README updated with the tier model, identity/flag prerequisites, and the phase map.

---

## 9. Suggested first step

Ship **Phase 0** as one PR: it delivers the harness + fixtures + multi-identity + flag detection and re-expresses today's smoke checks as scenarios — no new coverage, but every later phase becomes additive scenario files. Low risk, unblocks parallelism (phases 2–5 are largely independent once the runner exists), and immediately gives the T0 CI lane.
