# BE → FE Endpoint Sync Audit

**Date:** 2026-06-05
**Scope:** All backend REST endpoints cross-referenced against frontend `ApiClient` call sites.
**Method:** Enumerated 273 backend endpoints; matched each against FE API calls, resolving
`const BASE` URL composition and path-param wildcards; every candidate orphan re-verified by
reading the actual FE call site. `website/` excluded per project scope.

> Companion to the FE → BE direction. This direction finds backend endpoints with **no FE
> consumer** (dead surface or by-design machine endpoints). The `fe-be-contract-auditor`
> subagent can regenerate this audit against the live route table.

---

## Summary

~20 endpoints across 8 controllers have no FE consumer. They split into three classes:

1. **No FE consumer but live backend semantics** — distinct behavior + REST-level tests; "no FE call"
   does **not** make these dead. **Keep.** (An earlier revision wrongly tagged these "dead duplicates,
   safe to delete" — corrected below after reading the handler bodies and their tests.)
2. **No-FE-feature** — a whole controller or capability with no UI surface. Product call: build UI, or remove.
3. **By-design non-FE** — machine/peer endpoints (A2A, MCP, health, SSE token). Not bugs; keep.

> **Lesson (blast-radius before classification):** FE-unused ≠ dead. Three endpoints initially flagged
> for deletion turned out to be the HumanReview settlement path, the router-HITL continuation path, and
> deliberate run-cancellation — each with distinct service calls and integration tests. Always read the
> handler body + grep `src/test` before recommending a delete.

---

## Orphans (no FE consumer)

### No FE consumer but live backend semantics — KEEP (NOT deletable)

Verified 2026-06-05 by reading each handler body and its tests. These are **not** duplicates of the
similarly-named FE-used paths — they call different services / job handlers and are exercised by
REST-level integration tests.

| Controller | Endpoint | What it actually is | Why it is NOT a duplicate |
|---|---|---|---|
| `ApprovalsController` | `POST /{id}/decide` | Unified **HumanReview** settlement → `humanReviewService.decide(...)` (dispatches WorkflowStep / team-member gates by subjectType). | `/resolve` calls `approvalService.resolveApprovalForOrg(...)` (legacy ApprovalDTO flow) — different service, different response (`HumanReviewDecideResponse` vs `ApprovalDTO`). Driven via REST by `WorkflowHumanReviewE2eRuntimeTest`. |
| `WorkflowsController` | `POST /runs/{runId}/continue` | **Router-HITL continuation** → enqueues `WorkflowContinueJobHandler`. | `/resume` enqueues `WorkflowResumeJobHandler` — different job handler / lifecycle. `HumanReviewDecideRequest`, `RouteSelectorType`, `HitlRouteSelector` all reference `/continue` as the router path. Driven via REST by `WorkflowRouterE2eRuntimeTest`. |
| `WorkflowsController` | `DELETE /runs/{runId}` | Deliberate **run cancellation** → `WorkflowService.cancelWorkflowRun(...)`. | Documented contract (404 unknown/cross-tenant, idempotent 204 on terminal rows). Operator/programmatic cancel; absence of a FE button doesn't make it dead. |

### No FE feature — product decision needed

| Controller | Endpoints | Note |
|---|---|---|
| `SkillAdminController` | **entire controller (7):** `GET /api/v1/skills`, `POST /api/v1/skills`, `GET /{id}`, `PUT /{id}`, `DELETE /{id}`, `POST /{skillId}/agents/{agentId}`, `DELETE /{skillId}/agents/{agentId}` | No `features/skills` dir in FE. Skills is either planned-not-built or removable. |
| `BackgroundJobController` | `pause`, `resume`, `pause-state` | FE only calls list / `{id}/retry` / `status-summary`. |
| `AuthController` | `password-reset/request`, `password-reset/confirm`, `logout` | No self-service reset UI. FE `logout()` is a client-side token clear; admin reset uses `/api/admin/users/{id}`. |
| `ComposioCatalogController` | `GET /catalog`, `POST /catalog/import` | FE composio only uses actions / connection / config-drift. |
| `ModelCatalogController` | `GET /api/v1/models/catalog/*` | No FE consumer. |
| `RoutingEmbeddingsAdminController` | `POST /admin/routing-embeddings/backfill` | No FE consumer. |

### By-design non-FE (NOT bugs — keep)

| Surface | Endpoints |
|---|---|
| A2A | `/api/v1/a2a/**`, `A2aHealthController /api/v1/health` |
| MCP | `/mcp/sse`, `/mcp/messages` |
| SSE token | `SseTokenController /runs/{id}/sse-token` |

---

## False positives discarded

These looked like orphans but the FE **does** call them — it composes the URL from a `const BASE`
prefix or a path-param wildcard, so a naive string match misses them. Verified at the FE call site:

reflections · approvals `/pending` + `/resolve` · audit-logs `/export` · background-jobs `retry` +
`status-summary` · evaluations `suites` / `runs` · knowledge `status` / `stream` (via `ApiClient.stream`) ·
memories `optimize` / `stats` / `topics` · model `clone` · orchestration-decisions · runs `/{id}/events` ·
schedules `/{id}/runs` · runs `/stream` · users `/bulk` (`${BASE}/bulk`) · workflows `/{id}/runs` + `resume`

---

## Correction to prior notes

Backend **does** serve OpenAPI — springdoc exposes `/v3/api-docs` and `/swagger-ui.html`
(`SpringDocAppInitializer` runs at boot). Any earlier "no OpenAPI" claim is wrong; the live route
table is available for cross-checking.

---

## Independent re-confirmation (2026-06-05)

A second, independent audit pass (separate agent, re-deriving the FE↔BE crossing from scratch with
`BASE_URL='/api'` + path-param resolution) **confirmed every orphan above**. It proposed one delta —
that `SkillAdminController`'s two agent-binding routes (`POST`/`DELETE /{skillId}/agents/{agentId}`)
had been deleted from the backend. That delta was **checked against source and rejected**: both
mappings still exist (`SkillAdminController.java:68,76`). The controller remains 7 endpoints. No
findings were added or removed by the second pass.

## Recommended next steps

1. **No deletion of `/decide`, `/runs/{id}/continue`, `DELETE /runs/{id}`** — verified live (distinct
   semantics + integration tests). If anything, these are FE *gaps* (the UI doesn't yet expose
   HumanReview-decide, router-continue, or run-cancel), not BE dead code.
2. Product call on `SkillAdminController`: ship UI or remove the controller + repository + entity.
   (Whole controller, 7 endpoints — but confirm it's not driven by tests/A2A before removing, same
   discipline as step 1.)
3. Product call on the remaining no-FE capabilities (BackgroundJob pause/resume, Auth password-reset,
   Composio catalog, Model catalog, routing-embeddings backfill) — each is "build UI" vs "remove".
