# What Is a "Client" for Agent Manager?

_Analysis: what a client application needs (feature-wise + architecturally) to execute, test, and use Agent Manager (AGM) — and whether it should be its own Java + React stack._

---

## The key reframe: AGM *is* the backend

agent-manager already owns persistence (Postgres/pgvector), orchestration (DAG engine, teams), LLM providers, RAG, multi-tenant auth, HITL, FinOps, and observability — and it already ships a reference React client (`agent-manager-ui`). A "client" is therefore a **consumer of AGM's API**, not a peer stack that re-implements any of that.

### Direct verdict

| Question | Verdict |
|---|---|
| **Java backend in the client?** | **No.** A client needs zero Java. Duplicating a Spring backend creates a second source of truth for auth/state/orchestration. The only server-side code a client should ever have is a thin **BFF** (backend-for-frontend), and that should be a lightweight Node/Go/Bun/Python service — not Java. Java only makes sense if your "client" is actually another Spring service that wants AGM's SPI / A2A protocol in-process. |
| **React frontend in the client?** | **Only if the client is a UI** — and then React is a sensible default (matches AGM's stack, lets you reuse its patterns/components), but not required. If the client is a test harness, CLI, SDK, or service integration, it has **no frontend at all**. |

---

## What *any* client must handle (the AGM contract)

These are dictated by how AGM actually works, not generic API advice:

1. **JWT auth** — `POST /api/auth/login` → attach `Authorization: Bearer <token>` on every call. The token carries **no org claim**; AGM resolves the org server-side from the user record. The client just holds/attaches the token — it never manages tenancy itself.
2. **Streaming agent runs** — `POST /api/agents/{id}/runs/stream` returns **SSE / NDJSON over a POST**. Browser `EventSource` cannot POST, so a fetch-based SSE client is required (AGM's own UI uses `@microsoft/fetch-event-source`). The client renders token-by-token deltas **plus** activity / tool-trace frames.
3. **HITL pauses** — a run can return `PAUSED` mid-stream when a tool needs approval. A correct client **cannot assume synchronous completion**: it must surface an approval UI and resume via the approvals endpoints. This is the #1 thing naive clients get wrong.
4. **Sessions / conversation** — mint a session id, replay history (`GET /sessions/{id}/runs`), support multi-turn.
5. **Discovery** — list agents / templates / tools / models / knowledge bases so the user can pick what to run.
6. **Provider setup** — LLM keys live **server-side, per-org, encrypted**. The client never sends an LLM key; an admin configures it once (`POST /api/v1/provider-credentials`). Until then, runs return `400 "No API key configured"` — a client should detect and explain that, not treat it as a crash.
7. **Error taxonomy** — `401` (stale JWT / null org → re-login), `400` BusinessValidation (concurrency cap, media-to-non-vision-model, orchestration depth ≥ 5), stream errors. Handle each distinctly.

---

## Client archetypes — pick by purpose

| Purpose | Best shape | Frontend? | Server code? |
|---|---|---|---|
| **Test / exercise the API** (CI, smoke, load) | Typed SDK + script harness (TS or Python), or Bruno/Postman + an SSE assertion helper | none | none |
| **Ops / management UI** | **Reuse `agent-manager-ui`** — don't rebuild | ✔ (exists) | none |
| **End-user product** (embed a copilot / chat) | SPA → AGM, optionally via a BFF | ✔ | optional BFF |
| **Service-to-service** (another system uses agents) | Direct REST, or the **A2A** protocol AGM exposes | none | your service |
| **Tool consumer** | An **MCP** client against AGM's `/mcp/*` surface | none | none |

---

## Recommended product-grade architecture

For a real end-user client, this is the shape to build. It mirrors what AGM's own UI does — which is the proof it works against this API.

```
[ SPA ]  React 19 + TanStack Query (REST server-state) + a dedicated SSE stream manager
   │       @microsoft/fetch-event-source for POST-streamed runs (token + activity frames)
   │       minimal client state (Zustand for a couple cross-cutting bits) — cache is the source of truth
   │       typed API client CODEGEN'd from AGM's /v3/api-docs (openapi-typescript / orval)
   │
   ▼  (optional — only if the browser must not hold the token or hit AGM directly)
[ BFF ]  thin, STATELESS, language-agnostic (Node / Go / Bun) — NOT Java
   │       responsibilities: token custody / exchange, SSE pass-through, request aggregation,
   │       per-tenant credential injection, webhook receiver for async job completion, rate-limit shielding
   ▼
[ AGM ]  the platform — you add nothing here
```

Deployment: static SPA on a CDN / Caddy (exactly AGM's `agm-web` model) + optional BFF container. The contract stays honest because AGM enforces typed request/response schemas via arch tests, so OpenAPI codegen produces a clean, drift-detecting client.

**When you do NOT need the BFF:** an internal tool or trusted-network SPA can talk to AGM directly — CORS is already configurable (`APP_CORS_ALLOWED_ORIGIN_PATTERNS`). Add the BFF only when you need token custody, SSR, aggregation, or to keep AGM off the public internet.

---

## AGM-specific gotchas that shape the client

- **POST-streaming ⇒ no `EventSource`.** Budget for a fetch-SSE lib + reconnect logic up front.
- **Runs pause (HITL).** Model a run as an async state machine (`RUNNING → PAUSED → RUNNING → COMPLETED / FAILED`), not a request/response.
- **Provider keys are server-side.** Onboarding must include "admin adds a provider credential" or nothing runs.
- **Multi-tenant mapping.** If the product has its own users, decide early: map each to an AGM user/org, or use one service account and pass context. Don't invent org handling client-side — AGM owns it.
- **Health / observability.** For a test / monitoring client, prod exposes `/api/v1/health` (not `/actuator/*`), plus run telemetry + evaluation endpoints for assertions.

---

## Bottom line

The best client is a **thin, typed consumer of AGM's REST + SSE surface**:

- **No Java.**
- **React only if you need a UI** (and then it's a good default, not a requirement).
- **Optional non-Java BFF** for token / SSE / aggregation concerns.

Choose by goal:

- **Test / exercise AGM** → skip the UI; build a codegen'd SDK + SSE harness.
- **End-user product** → build the SPA (+ optional BFF) above.
- **Ops** → reuse `agent-manager-ui`.
