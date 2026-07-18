#!/usr/bin/env bash
# Walkthrough #27 — compliance cross-org probe
# ----------------------------------------------------------------------
# Closes the verification loop for PRs #931 (GDPR endpoints scope to
# caller's org), #932 (org_id on erasure_requests), and #933 (controller
# wires orgId through to scoped reads + payloads).
#
# Before #931, any ADMIN in any org could export any user's data
# (`@PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")`
# had no org check on the ADMIN branch). This was a P0 GDPR
# data-portability gap with full cross-tenant exposure, surfaced by the
# original Demo #27 against the demo seed.
#
# This walkthrough verifies the gate on all four compliance endpoints:
#
#   * GET    /api/compliance/export/{foreignUserId}     -> 404
#   * POST   /api/compliance/erasure-requests?userId=…  -> 404
#   * GET    /api/compliance/erasure-requests?userId=…  -> 404
#   * DELETE /api/compliance/erase/{foreignUserId}      -> 404
#
# Plus one sanity probe on the caller themselves to confirm auth is
# working (any non-4xx is acceptable — service-level export bugs are
# out of scope here; this script verifies the AUTHZ GATE only).
#
# Prerequisites
# -------------
# 1. AGM backend running on $AGM_BASE_URL.
# 2. Demo profile active so the foreign user exists in a different org
#    than the caller. Default expects PR #936 merged (demo-admin in
#    DEMO_ACME, sesker in DEFAULT_SYSTEM_ORG). Override FOREIGN_USER_ID
#    to any username that demonstrably lives in an org other than the
#    one the caller is in.
# 3. AGM_USER / AGM_PASSWORD credentials for an ADMIN in an org that
#    does NOT contain FOREIGN_USER_ID.
#
# Exit code 0 only when every cross-org call returns 404.

set -euo pipefail

AGM_BASE_URL="${AGM_BASE_URL:-http://localhost:8080}"
AGM_USER="${AGM_USER:-sesker}"
AGM_PASSWORD="${AGM_PASSWORD:-yamaha69}"
FOREIGN_USER_ID="${FOREIGN_USER_ID:-demo-admin}"

stage() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
fail()  { printf '\033[1;31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

require() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail "$label: expected HTTP $expected, got $actual"
  fi
  printf '  %-50s -> %s\n' "$label" "$actual"
}

# Same gate-not-blocked check, but accepts any non-4xx — used for the
# self-access sanity probe where the underlying service may return
# 200/500 depending on data state, and we only care that the AUTHZ gate
# let us through.
require_gate_open() {
  local label="$1" actual="$2"
  if [[ "$actual" =~ ^4 ]]; then
    fail "$label: gate blocked self-access (got 4xx $actual); auth or org config is wrong"
  fi
  printf '  %-50s -> %s (gate cleared)\n' "$label" "$actual"
}

stage "0. Auth as $AGM_USER"
TOKEN=$(curl -fsS -X POST "$AGM_BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$(printf '{"username":"%s","password":"%s"}' "$AGM_USER" "$AGM_PASSWORD")" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
[[ -n "$TOKEN" ]] || fail "login returned empty token"
AUTH=(-H "Authorization: Bearer $TOKEN")

stage "1. Sanity — caller can reach their own export (any non-4xx)"
CODE=$(curl -sS "${AUTH[@]}" -o /dev/null -w '%{http_code}' \
  "$AGM_BASE_URL/api/compliance/export/$AGM_USER")
require_gate_open "self export ($AGM_USER)            " "$CODE"

stage "2. Cross-org export — must 404"
CODE=$(curl -sS "${AUTH[@]}" -o /dev/null -w '%{http_code}' \
  "$AGM_BASE_URL/api/compliance/export/$FOREIGN_USER_ID")
require "GET export/$FOREIGN_USER_ID" 404 "$CODE"

stage "3. Cross-org submit erasure — must 404"
CODE=$(curl -sS "${AUTH[@]}" -X POST -o /dev/null -w '%{http_code}' \
  "$AGM_BASE_URL/api/compliance/erasure-requests?userId=$FOREIGN_USER_ID")
require "POST erasure-requests for foreign user" 404 "$CODE"

stage "4. Cross-org list erasure — must 404"
CODE=$(curl -sS "${AUTH[@]}" -o /dev/null -w '%{http_code}' \
  "$AGM_BASE_URL/api/compliance/erasure-requests?userId=$FOREIGN_USER_ID")
require "GET erasure-requests for foreign user " 404 "$CODE"

stage "5. Cross-org legacy delete — must 404"
CODE=$(curl -sS "${AUTH[@]}" -X DELETE -o /dev/null -w '%{http_code}' \
  "$AGM_BASE_URL/api/compliance/erase/$FOREIGN_USER_ID")
require "DELETE erase/$FOREIGN_USER_ID         " 404 "$CODE"

stage "RESULT"
printf '\033[1;32mPASS\033[0m — walkthrough #27 all cross-org assertions held; gate is closed.\n'
