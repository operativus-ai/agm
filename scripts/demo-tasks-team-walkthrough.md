# TeamMode.tasks end-to-end walkthrough

End-to-end smoke test for the TasksMode initiative (PRs #902-#909, #913) against
a real LLM. Until you run this, every PR in that initiative is verified only
against `FakeChatModel`. This is the first contact with a real provider.

## Prerequisites

1. **Backend up.** From repo root:
   ```bash
   docker-compose up -d              # Postgres + pgvector
   ./mvnw -pl agent-manager spring-boot:run
   ```
2. **Provider credential.** The coordinator (Claude Sonnet by default) needs
   an Anthropic API key wired to your org. Two options:
   - `.env` at repo root with `ANTHROPIC_API_KEY=sk-ant-…` (read at startup).
   - `POST /api/v1/provider-credentials` with the key and `provider=ANTHROPIC`,
     scoped to your caller org.
3. **Bearer token.** Login as an admin user; export the token:
   ```bash
   export AGM_BEARER="$(curl -s -X POST http://localhost:8080/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"<your-pw>"}' | jq -r .accessToken)"
   ```
4. **`agm.universal-dispatch.enabled=true`** — default in the dev profile.

## Run the script

```bash
./scripts/demo-tasks-team-walkthrough.sh
```

Optional overrides:
```bash
AGM_BASE_URL=http://localhost:8080 \
COORDINATOR_MODEL=claude-sonnet-4-6 \
WORKER_MODEL=claude-haiku-4-5 \
ORG_ID=DEFAULT_SYSTEM_ORG \
./scripts/demo-tasks-team-walkthrough.sh
```

Add `--cleanup` to delete the 4 agents the script creates.

## What it does

Seven stages:

1. **Health check** — `GET /actuator/health` confirms the backend is reachable.
2. **Create 3 workers** — `Researcher`, `Email Writer`, `Summarizer`. Plain
   non-team agents on the worker model (Haiku by default).
3. **Create the TASKS coordinator** — `isTeam=true, teamMode='TASKS'`, with
   members pointing at the 3 workers. `AgentClientFactory.resolveTools`
   (PR #908) auto-injects the four `TaskManagementTool` methods into this
   agent's `ChatClient.tools()` list.
4. **Run the coordinator** — `POST /api/agents/tt-demo-coordinator/runs` with
   a non-trivial multi-step prompt. `TasksOrchestrator.execute` runs the
   coordinator turn (PR #903), then drains the worker loop (PR #903), then
   re-runs the coordinator with the task-result summary (PR #909).
5. **Run-event timeline** — `GET /api/v1/runs/{runId}/events` returns the
   audit trail. Look for:
   - `TASK_CREATED` events from `TaskManagementTool.createTask` (PR #904 + #905)
   - `TASK_UPDATED` pairs (PENDING→IN_PROGRESS on dispatch, terminal on
     completion) from `TasksOrchestrator` (PR #905)
6. **Persisted task rows** — explicit DB query (admin endpoint is a follow-up;
   for now use psql or pgcli). Each row should show terminal status, result
   string, `dispatched_at` stamped (proof the atomic CAS won — D5 from the
   design doc).
7. **Final response** — the synthesized content. PR #909 means this is the
   *synthesis pass*, not the kickoff response.

## What success looks like

- Stage 3 returns `{id, isTeam:true, teamMode:"TASKS"}`.
- Stage 4 returns a run id and `status:"COMPLETED"`.
- Stage 5 timeline has at least 3 `TASK_CREATED` rows and ≥6 `TASK_UPDATED`
  rows (3 dispatches × 2 transitions).
- Stage 7 reads as a coherent answer covering competitors + outreach email +
  3-bullet summary. Should NOT read like the coordinator's first turn (which
  would just be an acknowledgment naming the subtasks).

## What failure looks like

- **No `TASK_CREATED` events.** Coordinator didn't call the tool. Most likely:
  the model picked doesn't respect tools, or the tool wasn't bound. Check
  `AgentClientFactory.resolveTools` output in app logs; should see four
  task tools resolved for the coordinator.
- **`TASK_UPDATED` pairs but no completion.** Workers are crashing —
  inspect the run-events payload for `RUN_FAILED` rows from worker agent ids.
  Probable cause: provider credential missing for worker model.
- **Final response is the kickoff acknowledgment.** Synthesis pass returned
  empty/blank, so PR #909's fallback kicked in. Inspect logs for
  `TasksOrchestrator synthesis pass failed`.
- **`No API key configured for provider`.** Provider credential gap. Either
  the `.env` key isn't wired in (check `AbstractDynamicModelProvider.resolveApiKey`
  trace in logs) or the `provider_credentials` row is for the wrong org.

## Followups gated on this walkthrough

The initiative's deferred polish items only become worth doing once you've
seen the demo work:

- FE `useRunStream.ts` + `TaskListPanel.tsx` — only useful when there's a
  realistic stream of `TASK_*` events to render.
- `FakeChatModel.respondWithSequence` test-infra — the E2E `Tag('integration')`
  tests for HITL approve/reject paths blocked on this. Worth fixing once
  the demo confirms the orchestrator path is right; otherwise the E2E might
  be testing the wrong shape.
- Per-task budget cap (D-future) — surfaces if the demo's coordinator burns
  more tokens than expected.
