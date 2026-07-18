#!/usr/bin/env bash
# Walkthrough #22 — workflow CONDITION + LOOP step creation
# ----------------------------------------------------------------------
# Closes the verification loop for PR #918 (fix(workflow): allow
# CONDITION/LOOP step creation via REST API). Before #918, a CONDITION
# step with a predicate expression in the agentId column (e.g.
# "contains:dollar") tripped strict agent-existence validation and the
# controller returned 404 with an empty body. LOOP steps had the same
# issue. After #918, both action types accept expression strings; AGENT
# steps still validate against the agent registry (regression guard).
#
# This walkthrough exercises four cases against a running BE:
#
#   1. AGENT step with a real seeded agent       → 201
#   2. CONDITION step with `contains:dollar`     → 201   ← was 404 pre-918
#   3. LOOP step with `max:5|until:done`         → 201   ← was 404 pre-918
#   4. AGENT step with a nonexistent agentId     → 404   ← regression guard
#
# Then lists the persisted steps and tears down the workflow.
#
# Exit code 0 only when all four cases match expectations.
#
# Prerequisites
# -------------
# 1. AGM backend running on $AGM_BASE_URL (default http://localhost:8080)
# 2. Demo profile active (so `tt-demo-researcher` exists, seeded by
#    demo-014 + the demo-tasks-team-walkthrough flow). If it doesn't
#    exist, override AGENT_ID to any seeded agent in your org.
# 3. AGM_USER / AGM_PASSWORD credentials for an ADMIN in the org that
#    owns AGENT_ID. Defaults to the screenshot login.
#
# Template note
# -------------
# This file is the reference shape for the rest of the walkthrough
# scripts under scripts/walkthroughs/. Keep:
#
#   * `set -euo pipefail` + explicit error messages on every assertion
#   * `require()` helper for status-code checks
#   * `cleanup()` trap so the workflow is always deleted, even on failure
#   * A `--keep` flag for debugging that suppresses cleanup
#   * Numbered demo ID matches the historical demo arc in commit messages

set -euo pipefail

AGM_BASE_URL="${AGM_BASE_URL:-http://localhost:8080}"
AGM_USER="${AGM_USER:-sesker}"
AGM_PASSWORD="${AGM_PASSWORD:-yamaha69}"
AGENT_ID="${AGENT_ID:-tt-demo-researcher}"

KEEP_WORKFLOW=0
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP_WORKFLOW=1 ;;
    *)      echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

stage() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
fail()  { printf '\033[1;31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

require() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail "$label: expected HTTP $expected, got $actual"
  fi
  printf '  %-50s -> %s\n' "$label" "$actual"
}

stage "0. Auth"
TOKEN=$(curl -fsS -X POST "$AGM_BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{"username":"%s","password":"%s"}' "$AGM_USER" "$AGM_PASSWORD")" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
[[ -n "$TOKEN" ]] || fail "login returned empty token"

AUTH=(-H "Authorization: Bearer $TOKEN")
JSON=(-H "Content-Type: application/json")

WF_ID="walkthrough22-$(date +%s)"

cleanup() {
  if (( KEEP_WORKFLOW == 1 )); then
    printf '\n\033[1;33mKEEP: leaving %s in place; delete it manually when done.\033[0m\n' "$WF_ID"
    return
  fi
  stage "Cleanup"
  curl -sS "${AUTH[@]}" -X DELETE "$AGM_BASE_URL/api/v1/workflows/$WF_ID" \
    -o /dev/null -w '  DELETE workflow -> %{http_code}\n'
}
trap cleanup EXIT

stage "1. Create workflow $WF_ID"
CODE=$(curl -sS "${AUTH[@]}" "${JSON[@]}" -X POST "$AGM_BASE_URL/api/v1/workflows" \
  -d "$(printf '{"id":"%s","name":"Walkthrough #22","description":"PR #918 verification"}' "$WF_ID")" \
  -o /dev/null -w '%{http_code}')
require "POST /workflows" 201 "$CODE"

stage "2. AGENT step with seeded agent (should succeed)"
CODE=$(curl -sS "${AUTH[@]}" "${JSON[@]}" -X POST "$AGM_BASE_URL/api/v1/workflows/$WF_ID/steps" \
  -d "$(printf '{"stepOrder":1,"agentId":"%s","action":"AGENT"}' "$AGENT_ID")" \
  -o /dev/null -w '%{http_code}')
require "AGENT step (real)        " 201 "$CODE"

stage "3. CONDITION step with predicate expression (was 404 before PR #918)"
CODE=$(curl -sS "${AUTH[@]}" "${JSON[@]}" -X POST "$AGM_BASE_URL/api/v1/workflows/$WF_ID/steps" \
  -d '{"stepOrder":2,"agentId":"contains:dollar","action":"CONDITION","onReject":"SKIP"}' \
  -o /dev/null -w '%{http_code}')
require "CONDITION step           " 201 "$CODE"

stage "4. LOOP step with bounds expression (was 404 before PR #918)"
CODE=$(curl -sS "${AUTH[@]}" "${JSON[@]}" -X POST "$AGM_BASE_URL/api/v1/workflows/$WF_ID/steps" \
  -d '{"stepOrder":3,"agentId":"max:5|until:done","action":"LOOP"}' \
  -o /dev/null -w '%{http_code}')
require "LOOP step                " 201 "$CODE"

stage "5. AGENT step with nonexistent agentId (regression guard — must still 404)"
CODE=$(curl -sS "${AUTH[@]}" "${JSON[@]}" -X POST "$AGM_BASE_URL/api/v1/workflows/$WF_ID/steps" \
  -d '{"stepOrder":4,"agentId":"no-such-agent-walkthrough22","action":"AGENT"}' \
  -o /dev/null -w '%{http_code}')
require "AGENT step (bogus id)    " 404 "$CODE"

stage "6. List steps and assert exactly 3 persisted"
STEPS_JSON=$(curl -fsS "${AUTH[@]}" "$AGM_BASE_URL/api/v1/workflows/$WF_ID/steps")
COUNT=$(echo "$STEPS_JSON" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')
[[ "$COUNT" == "3" ]] || fail "expected 3 persisted steps, got $COUNT"
echo "  GET /steps returned $COUNT rows"
echo "$STEPS_JSON" | python3 -c '
import sys, json
for s in json.load(sys.stdin):
    print(f"    step {s[\"stepOrder\"]:>2}  action={s[\"action\"]:<10}  agentId={s[\"agentId\"]}")
'

stage "RESULT"
printf '\033[1;32mPASS\033[0m — walkthrough #22 all assertions held\n'
