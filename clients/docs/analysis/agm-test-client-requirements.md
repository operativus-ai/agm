# Requirements Analysis: Full-Feature Test Client for Agent Manager

_Analysis only — no implementation. Defines what a client must cover to exercise/test **all** of Agent Manager's features from the outside, building on the archetype analysis in [`demo-what-is-a-client.md`](demo-what-is-a-client.md) and the existing skeleton at [`clients/test-client/`](../../test-client)._

---

## 1. Purpose & positioning

The existing `clients/test-client` covers the **core client contract** (auth, streamed runs, HITL frames, error taxonomy — 8 smoke steps). This document specifies what it takes to grow that into a **full-feature exercising client**: a harness that can drive, verify, and regression-check every externally reachable AGM capability through the same API a real customer would use.

**What it is:** a black-box, API-level feature exerciser (typed SDK + scripted scenarios + assertion suites).
**What it is not:** a replacement for the backend's own unit/integration tests (1,783 unit + ~1,650 integration tests already pin internals). The client tests the *contract and the composed behavior*, not implementation details.

Why this is worth building:
- The BE integration suite runs *inside* the Spring context with fakes (FakeChatModel, FakeEmbedding). A client-side harness is the only thing that exercises the **real wire**: serialization, auth filters, SSE framing, CORS, springdoc parity, real provider calls.
- It doubles as **living API documentation** and a **pre-release gate** against a deployed environment (dev, prod-compose, or the future Hetzner box) — the missing "smoke the real deployment" step in the CI→GHCR→deploy pipeline.

---

## 2. Feature inventory (ground truth)

Derived from the verified controller inventory (~53 controllers, 277 live endpoints as of the 2026-07-04 OpenAPI audit). Grouped into 12 testable domains:

| # | Domain | Key surfaces |
|---|--------|--------------|
| F1 | **Auth & identity** | `AuthController` (login/register/logout/password-reset), JWT lifecycle, `UserAdminController`, `AgentCredentialController` |
| F2 | **Agent lifecycle** | `AgentsController` (list/get/run), `AgentAdminController` (CRUD/import/export/versions/restore/topology/dx-metrics), `RegistryController`, templates |
| F3 | **Runs & streaming** | `POST /runs` sync, `/runs/stream` SSE, `/runs/background`, `RunsController`, `RunTelemetryController`, batch status, SSE event feeds (`RunEventSse`, `AgentEventSse`, `OrgEventSse`, `SseTokenController`) |
| F4 | **Sessions & memory** | `SessionController` (+ runs replay), `MemoryController`, `AgenticMemoryController`, `MemoryTaggingController`, `AgentReflectionController`, session summarization |
| F5 | **HITL & approvals** | `ApprovalsController` (pending/resolve/SLA), `EscalationsController`, tier gates (DESTRUCTIVE_TOOLS, FinOps flag), `SecurityInterceptsController` |
| F6 | **Knowledge / RAG** | `KnowledgeBaseController`, `KnowledgeController` (upload-batch/search/ingest), `KnowledgePreviewController`, `EmbeddingBackfillAdminController`, reranker flag, KB allowlists |
| F7 | **Workflows (DAG + flat)** | `WorkflowsController` (CRUD/edges/validate/run), 9 node kinds (Agent/Condition/Function/Join/Loop/Parallel/Router/SubWorkflow/Webhook), resume/frontier, `/workflows/ws` WebSocket, `WorkflowRunHistory` |
| F8 | **Teams & orchestration** | Teams CRUD/members/edges, 4 strategies (Planner/Router/Sequential/Swarm), delegation/handoff, `DelegationTopologyController`, `OrchestrationAggregateController`, depth limits |
| F9 | **Tools & integrations** | `ToolController` (catalog), tool invocation via runs, Composio (4 admin controllers), MCP (`McpController`, `McpLifecycleController`, `/mcp/*` server surface), `ExtensionController` (pre/post hooks), web-scrape/Firecrawl degradation |
| F10 | **Scheduling & jobs** | `SchedulesController` (cron/one-shot, agent+workflow targets, batches), `JobsController` + `BackgroundJobController` (queue, retry), A2A (`A2AController`, `A2aHealthController`, peer mesh) |
| F11 | **Governance & safety** | `PiiAdminController` (policies/bindings/audit-log), content safety tiers, prompt-injection guard, `ModelController` + `ModelCatalogController`, `ProviderCredentialAdminController`, `SettingsController`, `ConfigController`, FinOps (`FinOpsAdminController`, `BudgetExceededController`, daily caps), `DataRetentionController`, `ComplianceController`, `AuditLogController` + `SystemAuditLogController`, `IncidentResponseController` |
| F12 | **Observability & health** | `MonitoringController`, `ObservabilityController`, `DiagnosticsController`, `SloController`, aggregates (Safety/Session/Tool), `EvaluationController`, `/api/v1/health`, springdoc `/v3/api-docs` |
| — | *Feature-flagged (opt-in test lanes)* | Skills (`agm.skills.enabled`), Universal dispatch / org routing (`agm.universal-dispatch.enabled`), LLM reranker (`agent.rag.reranker.enabled`), FinOps HITL gate (`agm.hitl.finops.enabled`) |

---

## 3. Functional requirements

Requirement IDs are `TC-<domain>-<n>`. **P1** = core contract (must have), **P2** = feature depth, **P3** = admin/edge coverage.

### F1 — Auth & identity
- **TC-AUTH-1 (P1)** Login → JWT; Bearer on every call; assert `AuthResponse` shape (`token`, `roles`).
- **TC-AUTH-2 (P1)** Error taxonomy: bad creds → 401; garbage/expired token → 401 + local re-auth path; null-org principal behavior surfaced distinctly.
- **TC-AUTH-3 (P2)** Register → self-user has `ROLE_USER` only and null org (assert it *cannot* run org-scoped agents — this is tenancy design, and the harness must prove it, not stumble on it).
- **TC-AUTH-4 (P2)** Logout writes the `system_audits` LOGOUT row (verify via `SystemAuditLogController` as admin).
- **TC-AUTH-5 (P3)** Password-reset request/confirm flow (anti-enumeration contract: 200 either way; confirm rejects bad/expired token). Requires SMTP stub or log-scrape — mark env-dependent.
- **TC-AUTH-6 (P3)** Multi-role matrix: for a curated endpoint sample, assert USER vs ADMIN vs SUPER_ADMIN → 403/200 per the authz buckets (client-side mirror of `AdminEndpointAuthzRuntimeTest`).

### F2 — Agent lifecycle
- **TC-AGENT-1 (P1)** Discovery: list agents, get by id; assert schema.
- **TC-AGENT-2 (P2)** Full CRUD via admin: create from template → update (prompt/model/tools) → run → soft-delete → list `includeInactive` → restore.
- **TC-AGENT-3 (P2)** Export/import round-trip: export config JSON == re-imported agent behavior.
- **TC-AGENT-4 (P3)** Versions endpoint reflects updates; topology/dx-metrics respond with sane shapes.

### F3 — Runs & streaming
- **TC-RUN-1 (P1)** Streamed run: full event grammar (START → CONTENT_DELTA… → METRICS → STOP → `[DONE]`); ordered-event assertions (already partially in test-client).
- **TC-RUN-2 (P1)** Sync run (`POST /runs`) and background run (`/runs/background` → poll batch status) return consistent output for the same prompt/session.
- **TC-RUN-3 (P2)** Usage metrics: METRICS frame + `RunTelemetryController` agree on token counts; cost present when valuation resolves.
- **TC-RUN-4 (P2)** SSE event feeds: mint token via `SseTokenController`, subscribe to run/agent/org event streams, assert run lifecycle events arrive.
- **TC-RUN-5 (P2)** Concurrency cap: fire > limit parallel runs → assert the documented 400 BusinessValidation, not a hang.
- **TC-RUN-6 (P3)** Media input: image to a vision model succeeds; image to non-vision model → the documented 400.
- **TC-RUN-7 (P3)** Follow-up suggestions (`generateFollowups`) and reasoning deltas render when the model supports them.

### F4 — Sessions & memory
- **TC-SESS-1 (P1)** Multi-turn on one sessionId: turn 2 provably sees turn-1 context (seed a fact, ask for it back).
- **TC-SESS-2 (P1)** Replay: `GET /sessions/{id}/runs` returns all turns, ordered.
- **TC-SESS-3 (P2)** Session CRUD: list (paginated, agent-filtered), get, delete; deleted session's runs inaccessible.
- **TC-MEM-1 (P2)** Agentic memory: `save_memory` via an agent run → memory retrievable in a later session (memory ≠ chat history); assert org tagging via admin views.
- **TC-MEM-2 (P3)** Memory tagging + reflections endpoints respond and reflect run activity.

### F5 — HITL & approvals
- **TC-HITL-1 (P1)** Destructive tool (e.g. `run_python` agent) → PAUSED frame with approvalId → APPROVE → run resumes and completes; REJECT → run terminates with the rejection surfaced.
- **TC-HITL-2 (P2)** Approvals inbox: pending list shows the paused item; resolve via inbox (not just the frame-carried id); idempotency of double-resolve.
- **TC-HITL-3 (P2)** Swarm escalation path (escalationId analog) if a swarm-tier agent is provisioned.
- **TC-HITL-4 (P3)** FinOps gate: flip `agm.hitl.finops.enabled=true` (env-flag lane) → `bulkIngestDocumentationSite` pauses with TIER_2_FINOPS_BLOCK; default-off lane runs ungated.
- **TC-HITL-5 (P3)** SLA/timeout behavior on an unresolved approval (documented expiry outcome, not a hang).

### F6 — Knowledge / RAG
- **TC-RAG-1 (P1)** E2E grounding: create KB → upload docs (batch, async — poll ingestion) → `GET /api/knowledge/search` returns the seeded content with score ≥ threshold → an agent bound to the KB answers a question **only answerable from the doc**.
- **TC-RAG-2 (P2)** Similarity threshold: off-topic query returns no chunks (the 0.4 floor works on the wire).
- **TC-RAG-3 (P2)** KB allowlist: agent bound to KB-A cannot retrieve KB-B content (same org) — cross-KB isolation probe.
- **TC-RAG-4 (P2)** Tenant isolation: org-B user's search never returns org-A chunks (requires two provisioned orgs — see §5).
- **TC-RAG-5 (P3)** Preview endpoint, dedup on re-upload (SHA-256), delete-KB cascade, embeddings backfill (admin re-embed) sanity.
- **TC-RAG-6 (P3)** Reranker lane: `agent.rag.reranker.enabled=true` returns plausibly ordered results and degrades fail-open.

### F7 — Workflows
- **TC-WF-1 (P1)** Author linear DAG via API (nodes + edges) → validate → run → COMPLETED with per-node runs recorded.
- **TC-WF-2 (P2)** Node-kind matrix: one scenario per kind — CONDITION (true/false port routing), ROUTER (choice key), PARALLEL+JOIN (fan-out/fan-in, join separator), LOOP (until:), FUNCTION, WEBHOOK (callback target = the harness itself), SUBWORKFLOW.
- **TC-WF-3 (P2)** Fail-closed condition: unparseable expr routes false (client-visible proof of #1220).
- **TC-WF-4 (P2)** Pause/resume: HITL inside a DAG → frontier persists → resume completes (validates DAG-3c from outside).
- **TC-WF-5 (P3)** Live viewer feed: `/workflows/ws` WebSocket emits node-state transitions during a run.
- **TC-WF-6 (P3)** Retry/timeout: a failing node with retryMaxAttempts retries then fails; cycle-introducing edge is rejected by validation.

### F8 — Teams & orchestration
- **TC-TEAM-1 (P2)** Team CRUD + members; run a SEQUENTIAL team → output reflects member order.
- **TC-TEAM-2 (P2)** Strategy matrix: ROUTER routes by intent; PLANNER decomposes; SWARM handoffs visible in run events (one scenario each).
- **TC-TEAM-3 (P3)** Delegation/handoff tools: delegation topology endpoint reflects the run; depth cap (≥5 → 400) asserted.
- **TC-TEAM-4 (P3)** Team health/daily-spend aggregation responds with member-run-consistent numbers.

### F9 — Tools & integrations
- **TC-TOOL-1 (P1)** Tool catalog lists registered tools with categories; an agent run that invokes a benign tool (e.g. current-time/search) emits TOOL_START/TOOL_END frames.
- **TC-TOOL-2 (P2)** MCP surface: connect an MCP client to AGM's `/mcp/*` (SSE + messages), list tools, invoke one — AGM as MCP *server*.
- **TC-TOOL-3 (P3)** Composio: catalog/config endpoints respond; runtime-registered action appears in the merged tool provider (needs Composio creds — env-gated).
- **TC-TOOL-4 (P3)** Extensions: register pre/post hook → hook effect visible in run output.
- **TC-TOOL-5 (P3)** Graceful degradation: scrape tool with Firecrawl down → clean tool error, run completes (no hang); SSRF probe (internal URL) refused.

### F10 — Scheduling, jobs & A2A
- **TC-SCHED-1 (P2)** One-shot schedule (near-future) for an agent → fires → run recorded; cron create/list/pause/delete lifecycle.
- **TC-SCHED-2 (P3)** Workflow-target and batch variants; spot-batch queue visibility.
- **TC-JOB-1 (P2)** Background job queue: enqueue (e.g. bulk export) → poll status → terminal state; retry-on-failure visible.
- **TC-A2A-1 (P2)** A2A: register the harness as a peer (it already speaks HTTP) → AGM dispatches a task to it → harness answers → task completes. Health/mesh endpoints reflect peer state. *The test client doubles as the A2A peer stub — no extra infra.*

### F11 — Governance & safety
- **TC-GOV-1 (P1)** Provider credentials: admin adds key → agent runs succeed; remove → the documented provider-key 400 returns (the harness's WARN lane becomes an assertable transition).
- **TC-GOV-2 (P2)** PII: bind SSN policy to agent → prompt with SSN → response/audit shows redaction + `pii_audit_log` row (admin endpoint), org-scoped.
- **TC-GOV-3 (P2)** Prompt injection: canned injection phrase → run blocked/flagged per contract (SecurityException path), not silently executed.
- **TC-GOV-4 (P2)** FinOps: set a tiny org daily cap → burn it → next run rejected with the cap error; budget-exceeded signal visible; reset lane documented.
- **TC-GOV-5 (P2)** Models admin: CRUD + test-connection + catalog; settings read/update round-trip (e.g. default embedding model).
- **TC-GOV-6 (P3)** Audit trails: agent CRUD + runs + logout produce audit rows; immutability probe (no client-visible mutation of audit entries).
- **TC-GOV-7 (P3)** Compliance export + data-retention endpoints respond; erasure job completes (admin lane).
- **TC-GOV-8 (P3)** Tenant isolation sweep (client-side IDOR probe): org-B token against org-A's agent/session/KB/PII-audit ids → 404/403/empty everywhere. *Complements the server-side tenant-authz auditor with on-the-wire proof.*

### F12 — Observability & health
- **TC-OBS-1 (P1)** Liveness/reachability probes (already in test-client) + `/v3/api-docs` fetch.
- **TC-OBS-2 (P2)** **Spec-drift gate:** diff the live OpenAPI against the SDK's endpoint registry — fail when the SDK references a path the spec no longer has (client-side FE↔BE-contract-audit, automated).
- **TC-OBS-3 (P2)** Run telemetry/monitoring aggregates move when runs execute (before/after deltas, not absolute values).
- **TC-OBS-4 (P3)** Evaluations: create evaluation → execute → score recorded; SLO endpoint sanity.

---

## 4. Non-functional requirements

- **NFR-1 Determinism tiers.** Every scenario declares its tier: **T0 offline** (parsers, classifiers — no BE), **T1 live-deterministic** (CRUD, authz, shapes — real BE, no LLM), **T2 live-LLM** (needs a provider key; assertions must be semantic-tolerant — "contains the seeded fact", never exact-match prose). CI can run T0 always, T1 per-PR against prod-compose, T2 nightly/manually.
- **NFR-2 Environment-gap ≠ failure.** Missing provider key, no agents registered, flag-off lanes → WARN/SKIP with the fix printed (extends the existing taxonomy). Exit code fails only on contract violations.
- **NFR-3 Self-provisioning + cleanup.** Scenarios create their own fixtures (agents, KBs, workflows, users where allowed) with a run-unique prefix (`tc-<runid>-…`) and tear down after; a `cleanup` command sweeps orphans by prefix. Never depend on seed data beyond the bootstrap admin.
- **NFR-4 Idempotent re-runs.** Any scenario re-runnable back-to-back without manual resets (caps/budgets scenarios must restore prior values in `finally`).
- **NFR-5 Two identities minimum, three preferred.** admin (SUPER_ADMIN), org-user A, org-user B (second org) — B unlocks the isolation suite (TC-RAG-4, TC-GOV-8). Provisioning path: bootstrap admin creates users/orgs via `UserAdminController`.
- **NFR-6 Reporting.** Machine-readable results (JSON per scenario: id, tier, outcome, duration, evidence) + the human PASS/FAIL/WARN table; suitable as a CI artifact and as a deployment-gate report.
- **NFR-7 Budget guard.** T2 scenarios declare a token/cost ceiling; harness totals METRICS frames and aborts the run set if the ceiling is hit (don't let a test loop burn the org budget it's testing).
- **NFR-8 Zero runtime deps stays.** Node-native fetch/WebSocket; vitest/tsx dev-only. The A2A peer stub uses `node:http`.

---

## 5. Environment & fixture prerequisites

| Prereq | Needed for | Notes |
|---|---|---|
| Booted BE (dev profile or prod-compose) | everything ≥ T1 | `docker compose up -d` + `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` |
| Bootstrap admin credentials | all admin lanes | `ADMIN_BOOTSTRAP_*` or provisioned dev admin |
| One LLM provider credential | all T2 | via `POST /api/v1/provider-credentials`; absence must degrade to WARN |
| Second org + user | isolation suites | harness can self-provision via admin |
| Flag lanes | HITL-FinOps, reranker, skills, universal-dispatch | run BE with the flag env-var set; harness reads which lanes are active from `ConfigController`/settings and skips inactive ones |
| SMTP (optional) | TC-AUTH-5 | otherwise SKIP |
| Composio creds (optional) | TC-TOOL-3 | otherwise SKIP |
| Public callback reachability | TC-WF-2 webhook, TC-A2A-1 | harness opens a local `node:http` listener; BE must reach it (same host in dev) |

---

## 6. Architecture recommendation

**Extend `clients/test-client` — do not start a new project.** The SDK core (http/sse/stream/agm), error taxonomy, and reporter are exactly the foundation this needs. Growth path:

```
clients/test-client/
  src/sdk/            grow AgmClient by domain module (admin.ts, knowledge.ts, workflows.ts,
                      teams.ts, schedules.ts, governance.ts, ws.ts, a2a-peer.ts)
  src/fixtures/       self-provisioning helpers + prefix cleanup (NFR-3)
  src/scenarios/      one file per TC-domain; each scenario = {id, tier, run(ctx), evidence}
  src/report/         JSON + console reporters (NFR-6)
  smoke.ts            stays as the 2-minute core-contract pass
  full.ts             the tiered full-feature run (filter by tier/domain/id)
  tests/              vitest wrappers over the same scenarios (CI mode)
```

Key additions over today: a **WebSocket client** (workflow live viewer), an **embedded HTTP listener** (webhook node + A2A peer), an **OpenAPI drift checker** (TC-OBS-2), and the **fixture manager**. Everything else is more of the same patterns already proven in the skeleton.

**Sequencing (suggested):**
1. **Phase 1 (P1 set)** — TC-AUTH-1/2, AGENT-1, RUN-1/2, SESS-1/2, HITL-1, RAG-1, WF-1, TOOL-1, GOV-1, OBS-1 → "every major domain touched once" (≈ a deep smoke).
2. **Phase 2 (P2 set)** — feature depth: node-kind matrix, strategy matrix, isolation probes, FinOps cap, MCP, A2A peer, spec-drift gate.
3. **Phase 3 (P3 set)** — admin/edge/flag lanes, SLA/timeout edges, compliance/retention.

---

## 7. Explicitly out of scope

- UI testing (that's `agent-manager-ui`'s Vitest/visual-regression concern).
- Load/performance testing (different tool class; the concurrency-cap probe is a contract test, not a load test).
- BE internals (advisor ordering, repo queries — owned by the backend suites).
- Infra/deploy validation beyond the health/spec probes (owned by the deploy runbooks).
- `website/` — out of scope per project convention.

---

## 8. Traceability summary

Coverage counts: **58 requirements** (12 P1 · 27 P2 · 19 P3) across 12 domains + 4 flag lanes. Every controller group in the inventory maps to ≥1 requirement; the only surfaces intentionally untested are server-internal (SSE token plumbing tested indirectly via TC-RUN-4, MCP `/messages` via TC-TOOL-2).
