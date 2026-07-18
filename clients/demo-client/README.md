# AGM Demo Client

A minimal, typed consumer of Agent Manager's REST + SSE surface — the reference
implementation of the "thin client" architecture in
[`clients/docs/analysis/demo-what-is-a-client.md`](../docs/analysis/demo-what-is-a-client.md).

**No Java. No backend of its own.** Just a React 19 SPA that demonstrates the
four things every AGM client must get right:

1. **JWT auth** — `POST /api/auth/login`, Bearer on every call, 401 → re-login.
   The token carries no org claim; AGM resolves tenancy server-side.
2. **POST-streamed runs** — `POST /api/agents/{id}/runs/stream` returns SSE
   frames over a POST response. `EventSource` can't do that, so
   [`src/api/stream.ts`](src/api/stream.ts) reads the body stream manually
   (`data:` lines → JSON frames → `[DONE]` sentinel).
3. **HITL pauses** — a run is an async state machine, not request/response.
   On a `PAUSED` frame the chat surfaces Approve/Reject and resolves via
   `POST /api/v1/approvals/{approvalId}/resolve`.
4. **Error taxonomy** — problem+json `detail` surfaced verbatim (e.g.
   `400 "No API key configured for provider …"` means an admin needs to add a
   provider credential — it is not a client bug).

## Stack

- React 19 + TypeScript + Vite (dev server on **:5174**)
- TanStack Query for REST server-state (agents list)
- Hand-rolled ~70-line SSE reader for the run stream (no lib needed)
- Plain CSS — no framework, ~150 lines

## Run it

```bash
# 1. Boot the AGM backend (from agent-manager/):
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # needs docker compose up -d first

# 2. Start the demo client (from clients/demo-client/):
npm install
npm run dev          # http://localhost:5174  (proxies /api → :8080)
```

Sign in with a dev user, pick an agent, chat. Tool activity, reasoning deltas,
HITL approval gates, and per-run token/cost metrics all render inline.

## Layout

```
src/
  types.ts            wire types (mirror of the AGM contract; codegen candidate)
  api/
    client.ts         typed fetch wrapper + token storage + problem+json errors
    agm.ts            endpoint bindings: auth / agents / sessions / approvals
    stream.ts         the POST-SSE reader (the part naive clients get wrong)
  pages/
    LoginPage.tsx     JWT login
    AgentPickerPage.tsx  agent discovery (GET /api/agents)
    ChatPage.tsx      streaming chat + HITL approve/reject + usage metrics
  App.tsx             auth gate + view switching (deliberately router-less)
```

## What this demo deliberately skips

- **BFF** — it talks to AGM directly through the dev proxy; add a thin
  Node/Go BFF only for token custody / aggregation in a real product.
- **Codegen** — `types.ts` is hand-mirrored for readability; a production
  client should generate from `/v3/api-docs` (openapi-typescript / orval).
- **Session replay & media** — `GET /sessions/{id}/runs` binding exists in
  `api/agm.ts` but the UI keeps one fresh session per visit.
