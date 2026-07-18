#!/usr/bin/env bash
# Walkthrough #14 — agent create / duplicate-ID returns 409
# ----------------------------------------------------------------------
# Closes the verification loop for the P0 agent-create chain merged this
# arc:
#
#   * PR #923 — `GlobalExceptionHandler` maps
#               `DataIntegrityViolationException` to 409 with a `hint`
#               property carrying the offending constraint name.
#   * PR #924 — `AgentAdminService.createAgent` / `importAgent` set
#               `entity.setVersion(null)` so `agentRepository.save()`
#               routes new entities through `em.persist()` (INSERT)
#               instead of `em.merge()` (UPDATE → StaleObjectStateException
#               → false-positive 409 every time on a fresh ID).
#   * PR #926 — `GET /api/admin/agents/{id}` exists. Used here as a
#               sanity probe after the successful POST.
#
# Before this chain, the very first `POST /api/admin/agents` of a session
# returned 409 "Stale Data Conflict" for a brand-new ID; if the bogus
# 409 was somehow bypassed (e.g., by direct SQL insert) a subsequent
# duplicate POST would 500 because no handler caught the underlying PK
# violation.
#
# Script flow:
#
#   1. POST a fresh agent           -> 201 (proves persist() switch)
#   2. GET that agent by id         -> 200 (proves #926 endpoint exists)
#   3. POST the SAME id again       -> 409 with `hint` set
#                                       (proves #923 handler)
#   4. DELETE the agent             -> 204 (cleanup)
#
# Exit code 0 only when every assertion holds.
#
# Prerequisites
# -------------
# 1. AGM backend running on $AGM_BASE_URL.
# 2. AGM_USER / AGM_PASSWORD credentials with ADMIN. Defaults to the
#    demo screenshot login (sesker / yamaha69).

set -euo pipefail

AGM_BASE_URL="${AGM_BASE_URL:-http://localhost:8080}"
AGM_USER="${AGM_USER:-sesker}"
AGM_PASSWORD="${AGM_PASSWORD:-yamaha69}"

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

AGENT_ID="walkthrough14-$(date +%s)"
BODY=$(python3 -c '
import json, sys
print(json.dumps({
    "agentId":               sys.argv[1],
    "name":                  "Walkthrough #14",
    "description":           "Verifies duplicate-ID 409 (PRs #923/#924/#926).",
    "instructions":          "Stub agent — never invoked.",
    "model":                 "claude-4-5-haiku",
    "isTeam":                False,
    "active":                True,
    "memoryEnabled":         False,
    "tools":                 [],
    "isReasoningEnabled":    False,
    "requiresPiiRedaction":  False,
    "approvedForProduction": False,
    "maintenanceMode":       False,
    "enforceJsonOutput":     False,
    "securityTier":          1,
    "complianceTier":        "TIER_1_STANDARD",
}))' "$AGENT_ID")

cleanup() {
  stage "Cleanup"
  curl -sS "${AUTH[@]}" -X DELETE "$AGM_BASE_URL/api/admin/agents/$AGENT_ID" \
    -o /dev/null -w '  DELETE agent -> %{http_code}\n' || true
}
trap cleanup EXIT

stage "1. POST fresh agent (proves PR #924's persist() switch)"
CODE=$(curl -sS "${AUTH[@]}" "${JSON[@]}" -X POST "$AGM_BASE_URL/api/admin/agents" \
  -d "$BODY" -o /dev/null -w '%{http_code}')
require "POST /api/admin/agents (fresh id)" 201 "$CODE"

stage "2. GET that agent by id (proves PR #926 endpoint exists)"
CODE=$(curl -sS "${AUTH[@]}" -o /dev/null -w '%{http_code}' \
  "$AGM_BASE_URL/api/admin/agents/$AGENT_ID")
require "GET /api/admin/agents/$AGENT_ID" 200 "$CODE"

stage "3. POST same id again (proves PR #923 handler maps PK violation to 409)"
RESPONSE_FILE=$(mktemp)
CODE=$(curl -sS "${AUTH[@]}" "${JSON[@]}" -X POST "$AGM_BASE_URL/api/admin/agents" \
  -d "$BODY" -o "$RESPONSE_FILE" -w '%{http_code}')
require "POST /api/admin/agents (duplicate id)  " 409 "$CODE"

HINT=$(python3 -c '
import json, sys
try:
    body = json.load(open(sys.argv[1]))
    print(body.get("hint", ""))
except Exception:
    print("")
' "$RESPONSE_FILE")
rm -f "$RESPONSE_FILE"
if [[ -z "$HINT" ]]; then
  fail "duplicate POST did not surface a `hint` (PR #923 contract is hint-on-DataIntegrityViolation)"
fi
printf '  hint surfaced: %s\n' "$HINT"

stage "RESULT"
printf '\033[1;32mPASS\033[0m — walkthrough #14 all assertions held; create + duplicate-409 chain is wired.\n'
