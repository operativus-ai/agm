#!/usr/bin/env bash
# demo-tasks-team-walkthrough.sh
#
# End-to-end smoke test of the TeamMode.tasks initiative (PRs #902-#909, #913).
# Stands up a TASKS-mode team (coordinator + 3 workers) in the running AGM
# backend, fires a non-trivial prompt at POST /api/runs, and tails the run-event
# stream so you can watch the coordinator delegate tasks, workers execute, and
# the coordinator synthesize a final answer.
#
# Why this exists: every PR in the TasksMode initiative was "verified" against
# the unit suite + integration tests with FakeChatModel. This script is the
# first time we exercise the full path with a real LLM. Run it and see what
# actually happens.
#
# Prerequisites:
#   1. AGM backend running on localhost (or set AGM_BASE_URL).
#      docker-compose up -d  &&  ./mvnw spring-boot:run
#   2. An admin bearer token. Either grab from the login endpoint or set
#      AGM_BEARER directly:
#        export AGM_BEARER="$(curl -s -X POST $AGM_BASE_URL/auth/login \
#          -H 'Content-Type: application/json' \
#          -d '{\"username\":\"admin\",\"password\":\"<pw>\"}' | jq -r .accessToken)"
#   3. Provider credential configured for the caller's org. Either via the
#      admin endpoint POST /api/v1/provider-credentials (see ProviderCredential
#      docs) OR by setting ANTHROPIC_API_KEY in the .env file the app loaded.
#      The TASKS coordinator's model_id must reference a model whose provider
#      has a credential — otherwise the run fails with "No API key configured".
#   4. agm.universal-dispatch.enabled=true in application.properties (default
#      is on in the dev profile).
#
# Environment variables (override at the call site):
set -euo pipefail

AGM_BASE_URL="${AGM_BASE_URL:-http://localhost:8080}"
AGM_BEARER="${AGM_BEARER:-}"
COORDINATOR_MODEL="${COORDINATOR_MODEL:-claude-sonnet-4-6}"
WORKER_MODEL="${WORKER_MODEL:-claude-haiku-4-5}"
ORG_ID="${ORG_ID:-DEMO_ACME}"

if [[ -z "$AGM_BEARER" ]]; then
  echo "ERROR: AGM_BEARER not set. Export it before running:"
  echo "  export AGM_BEARER=\"\$(curl ... /auth/login | jq -r .accessToken)\""
  exit 1
fi

AUTH_HDR=(-H "Authorization: Bearer $AGM_BEARER")
JSON_HDR=(-H "Content-Type: application/json")

stage() {
  printf "\n\033[1;36m=== %s ===\033[0m\n" "$*"
}

# ──────────────────────────────────────────────────────────────────────────
# Stage 1: confirm the backend is up + has TASKS support compiled in
# ──────────────────────────────────────────────────────────────────────────
stage "1. Backend health"
curl -s -fsS "$AGM_BASE_URL/actuator/health" | jq -c .

# ──────────────────────────────────────────────────────────────────────────
# Stage 2: create 3 worker agents. Each owns one capability area.
# ──────────────────────────────────────────────────────────────────────────
stage "2. Create 3 worker agents"
for spec in \
  "tt-demo-researcher|Researcher|You research a topic. Find facts, cite sources, summarize. Plain prose, no bullet points." \
  "tt-demo-writer|Email Writer|You draft a short outreach email based on the brief you are given. Friendly tone, under 150 words." \
  "tt-demo-summarizer|Summarizer|You take a list of inputs and produce a 3-bullet executive summary." ; do
  IFS='|' read -r aid aname ainstr <<<"$spec"
  echo "  creating worker $aid"
  curl -s "${AUTH_HDR[@]}" "${JSON_HDR[@]}" -X POST "$AGM_BASE_URL/api/admin/agents" \
    -d "$(jq -n \
      --arg id "$aid" --arg name "$aname" --arg instr "$ainstr" --arg model "$WORKER_MODEL" \
      '{id:$id, name:$name, description:"TT walkthrough worker", instructions:$instr,
        modelId:$model, active:true, isTeam:false, securityTier:1,
        complianceTier:"TIER_1_STANDARD", maintenanceMode:false}')" \
    | jq -c '{id, name, modelId}'
done

# ──────────────────────────────────────────────────────────────────────────
# Stage 3: create the TASKS coordinator. is_team=true, team_mode='TASKS'.
# The auto-binding in AgentClientFactory (PR #908) will surface the four
# TaskManagementTool methods to this agent's ChatClient.
# ──────────────────────────────────────────────────────────────────────────
stage "3. Create TASKS coordinator"
COORDINATOR_INSTRUCTIONS='You coordinate a small team. When asked for a multi-step deliverable, you MUST: 1) Use the createTask tool to enqueue tasks. Provide title, description, assigneeAgentId from this list: tt-demo-researcher, tt-demo-writer, tt-demo-summarizer. Use dependencies to order tasks (a task deps must complete before it starts). 2) After enqueueing the tasks, respond with a brief acknowledgment naming each subtask. The system will dispatch tasks to assignees and re-engage you with results for a final synthesis. 3) Do not do any of the work yourself; delegate everything.'
curl -s "${AUTH_HDR[@]}" "${JSON_HDR[@]}" -X POST "$AGM_BASE_URL/api/admin/agents" \
  -d "$(jq -n \
    --arg id "tt-demo-coordinator" --arg name "Tasks Coordinator (demo)" \
    --arg instr "$COORDINATOR_INSTRUCTIONS" --arg model "$COORDINATOR_MODEL" \
    '{id:$id, name:$name, description:"TT walkthrough coordinator",
      instructions:$instr, modelId:$model, active:true,
      isTeam:true, teamMode:"TASKS", members:["tt-demo-researcher","tt-demo-writer","tt-demo-summarizer"],
      securityTier:1, complianceTier:"TIER_1_STANDARD", maintenanceMode:false}')" \
  | jq -c '{id, isTeam, teamMode}'

# ──────────────────────────────────────────────────────────────────────────
# Stage 4: configure org-default routing so POST /api/runs (no agentId in URL)
# dispatches to our coordinator. We use the explicit routerAgentId path on
# the universal-dispatch endpoint instead of /api/runs to avoid coupling this
# demo to the routing-config UI. We'll hit POST /api/runs with options.routerAgentId
# OR we'll just call POST /api/agents/{coordId}/runs directly.
#
# (Simpler path: dispatch to coordinator by id via the standard agent-runs
# endpoint. The coordinator-as-team will trigger TasksOrchestrator.execute.)
# ──────────────────────────────────────────────────────────────────────────
stage "4. Run the coordinator"
PROMPT="Plan a market-entry brief for a hypothetical product called 'PolyForge', a 3D-printing
SaaS for industrial parts. Research 3 competitors, draft a 100-word outreach email to a
prospective design partner, and produce a 3-bullet executive summary tying it all together.
Delegate the work to the team."

RESPONSE=$(curl -s "${AUTH_HDR[@]}" "${JSON_HDR[@]}" -X POST "$AGM_BASE_URL/api/agents/tt-demo-coordinator/runs" \
  -d "$(jq -n --arg msg "$PROMPT" '{message:$msg}')")

RUN_ID=$(echo "$RESPONSE" | jq -r .runId)
echo "  runId=$RUN_ID"
echo "  status=$(echo "$RESPONSE" | jq -r .status)"

# ──────────────────────────────────────────────────────────────────────────
# Stage 5: tail the run-event timeline. You should see TASK_CREATED rows for
# each task the coordinator enqueued, followed by TASK_UPDATED rows for each
# dispatch (PENDING -> IN_PROGRESS) and terminal transition (-> COMPLETED).
# ──────────────────────────────────────────────────────────────────────────
stage "5. Run-event timeline"
curl -s "${AUTH_HDR[@]}" "$AGM_BASE_URL/api/v1/runs/$RUN_ID/events" | jq -c '.[] | {ts:.eventTs, type:.eventType, payload:.payload}'

# ──────────────────────────────────────────────────────────────────────────
# Stage 6: list the tasks the coordinator created. Each row should show
# status=COMPLETED with a result string, assigneeAgentId set, dispatched_at
# stamped (proof the atomic CAS won).
# ──────────────────────────────────────────────────────────────────────────
stage "6. Persisted task rows"
echo "  (use the admin DB query or wait for the dedicated /tasks listing endpoint)"

# ──────────────────────────────────────────────────────────────────────────
# Stage 7: the final synthesized response. This is what the user gets.
# ──────────────────────────────────────────────────────────────────────────
stage "7. Final response (synthesis pass)"
echo "$RESPONSE" | jq -r .content

# ──────────────────────────────────────────────────────────────────────────
# Cleanup: leave the agents in place so you can re-run. Add --cleanup as the
# first arg to delete them.
# ──────────────────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--cleanup" ]]; then
  stage "Cleanup"
  for aid in tt-demo-coordinator tt-demo-researcher tt-demo-writer tt-demo-summarizer; do
    curl -s "${AUTH_HDR[@]}" -X DELETE "$AGM_BASE_URL/api/admin/agents/$aid" -w "  %{http_code} $aid\n" -o /dev/null
  done
fi

echo ""
echo "Done. What you should have observed:"
echo "  - Stage 5 timeline contains TASK_CREATED for each enqueued task and TASK_UPDATED"
echo "    pairs (PENDING->IN_PROGRESS and IN_PROGRESS->COMPLETED) for each."
echo "  - Stage 7 final response synthesizes the workers' outputs into a coherent answer"
echo "    (not the coordinator's kickoff turn — that would be the pre-#909 behavior)."
echo ""
echo "If you see fewer TASK_CREATED events than expected, the coordinator probably"
echo "didn't recognize the createTask tool. Check that PR #908 is in main (it should"
echo "be) and that the model you're using respects tool definitions (Claude Sonnet/Opus do)."
