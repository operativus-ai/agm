# AGM Test Client

Headless test client for Agent Manager — the **"Test / exercise the API"
archetype** from [`clients/docs/analysis/demo-what-is-a-client.md`](../docs/analysis/demo-what-is-a-client.md):
a typed SDK + SSE assertion harness. **No UI, no Java, zero runtime deps**
(Node-native `fetch`).

It consumes the same verified AGM wire contract as the other client archetypes in
this workspace.

## What it covers (the AGM client contract)

| Contract point | Where |
|---|---|
| JWT auth, Bearer everywhere, 401 taxonomy | `src/sdk/http.ts` (`AgmApiError.kind = 'auth'`) |
| SSE-over-POST run streaming | `src/sdk/sse.ts` (pure parser) + `src/sdk/stream.ts` (`collectRun`) |
| HITL pauses as run outcomes | `RunResult.paused` + `resolveToolApproval` / `resolveEscalation` |
| Sessions & multi-turn replay | `sessionRuns()` + smoke steps 5–6 |
| Discovery | `listAgents()` |
| Provider-key gap ≠ client bug | `AgmApiError.kind = 'provider-key'` → smoke reports **WARN**, never FAIL |
| Error taxonomy | `classifyError()`: auth / provider-key / validation / server |
| Liveness probing | `isReachable()` (any HTTP response = up) + `healthStatus()` (`/api/v1/health`) |

## Run

```bash
npm install

# Offline tests — always green, no backend needed (SSE parser + error classifier):
npm test

# Live smoke against a running BE (boot it first: ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev):
cp .env.example .env        # fill AGM_USERNAME / AGM_PASSWORD
set -a; source .env; set +a
npm run smoke               # scripted E2E: auth → discovery → stream → session → taxonomy

# Live vitest suite (same contract, CI-assertable):
AGM_LIVE=1 npm test
```

## Scenario harness (`npm run full`)

Beyond the fixed 8-step `smoke`, the harness runs a **registry of scenarios**
with uniform gating and reporting — the foundation for the full-feature build in
[`../docs/analysis/agm-test-client-implementation-plan.md`](../docs/analysis/agm-test-client-implementation-plan.md).

```bash
npm run full                  # run everything the environment allows
npm run full -- --dry-run     # list discovered scenarios + gating, run nothing
npm run full -- --tier=T0     # offline only (no backend)
npm run full -- --tier=T1     # + live-no-LLM
npm run full -- --domain=F3   # one domain (F1..F12)
npm run full -- --id=TC-AUTH-1,TC-RUN-1
npm run cleanup               # sweep orphaned tc-* fixtures (needs admin)
```

**Tiers (NFR-1):** every scenario is stamped `T0` offline / `T1` live-no-LLM /
`T2` live-LLM. Unmet prerequisites → **SKIP** with a reason; a missing provider
key on a T2 scenario → **WARN** (environment gap, not a contract failure). The
process exits non-zero only on **FAIL**. Each run writes `reports/report-<id>.json`.

**Identities (NFR-5):** `AGM_ADMIN_*` (unlocks admin lanes), plus optional
`AGM_USER_A_*` / `AGM_USER_B_*` (a second org unlocks the Phase-5 isolation
suite). Legacy `AGM_USERNAME/PASSWORD` still resolve as admin.

**Flag lanes:** the harness probes `agm.skills.enabled` /
`agm.universal-dispatch.enabled` behaviorally (their controllers 404 when off);
`reranker` / `finops-gate` have no probe, so set `AGM_FLAG_RERANKER` /
`AGM_FLAG_FINOPS` to run those lanes. Scenarios needing an off/unknown flag SKIP.

**Budget guard (NFR-7):** set `AGM_BUDGET_USD` to cap T2 spend — the runner stops
launching LLM scenarios once the ceiling is crossed.

> **Status:** Phase 0 (harness foundation) + the ported core-contract scenarios
> are implemented. Phases 1–5 append scenario files under `src/scenarios/`.

### Smoke output

Each step prints ✅ PASS / ❌ FAIL / ⚠️ WARN / ⏭️ SKIP and the process exits 1
only on FAIL. WARN marks environment gaps (no agents registered, no provider
key, health 401 under the dev profile) — real conditions a client must expect,
distinct from contract violations.

## Notes

- **Credentials:** use a dev user whose `org_id` is set. Self-registered users
  have null org_id and fail agent-scope resolution — that's AGM tenancy design,
  not a harness bug.
- **`/api/v1/health` may 401 under the dev profile** — the smoke treats any HTTP
  answer as liveness and marks non-200 as WARN (prod serves it 200 via Caddy).
- **A PAUSED run is a pass-able outcome:** HITL-gated agents pause mid-stream by
  design. The harness surfaces `RunResult.paused` (with `approvalId`) so a test
  can resolve and re-assert rather than time out.
- `types.ts` is hand-mirrored from the verified contract; codegen from
  `/v3/api-docs` is the production-grade upgrade path.
