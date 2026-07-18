#!/usr/bin/env bash
# scripts/check-remove-by.sh — fails build when any tracked source file under
# agent-manager/src/ contains `// REMOVE BY: YYYY-MM-DD` whose date has passed today.
#
# Operates on the §9-MEM-2 / §9-VT trip-wire convention introduced by PR #239's T014:
# any test that is deliberately RED (a known-failure pinned to an outside-this-PR fix)
# carries a `// REMOVE BY: YYYY-MM-DD` comment in its class-level javadoc. This script
# is the automated cleanup-discipline mechanism — flips the trip-wire from
# comment-only to PR-blocking once the date passes.
#
# Cross-platform: BSD `date -j -f` (macOS) and GNU `date -d` (Linux/CI) — uname-branched.
# Kill-switch: AGM_SKIP_REMOVE_BY_CHECK=1 exits 0 immediately (bootstrap-deadlock escape).
#
# Fixture true-positive  (would fail):  // REMOVE BY: 2020-01-01
# Fixture false-positive (passes):      // REMOVE BY: 2099-12-31
# Innocent text containing the literal "REMOVE BY:" without `//` and ISO date is ignored.

set -euo pipefail

[ "${AGM_SKIP_REMOVE_BY_CHECK:-0}" = "1" ] && { echo "check-remove-by.sh: skipped (AGM_SKIP_REMOVE_BY_CHECK=1)"; exit 0; }

today_epoch=$(date +%s)
to_epoch() {
    case "$(uname -s)" in
        Darwin) date -j -f "%Y-%m-%d" "$1" +%s 2>/dev/null ;;
        *)      date -d "$1" +%s 2>/dev/null ;;
    esac
}

violations=0
# Anchored regex: '//' + spaces + 'REMOVE BY:' + spaces + ISO-8601 date.
pattern='//[[:space:]]*REMOVE BY:[[:space:]]*[0-9]{4}-[0-9]{2}-[0-9]{2}'
while IFS= read -r match; do
    file="${match%%:*}"; rest="${match#*:}"
    line="${rest%%:*}"; text="${rest#*:}"
    iso=$(echo "$text" | grep -Eo '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
    target_epoch=$(to_epoch "$iso") || { echo "check-remove-by.sh: malformed date '$iso' at $file:$line"; violations=$((violations+1)); continue; }
    [ -z "$target_epoch" ] && { echo "check-remove-by.sh: malformed date '$iso' at $file:$line"; violations=$((violations+1)); continue; }
    if [ "$today_epoch" -gt "$target_epoch" ]; then
        echo "check-remove-by.sh: PAST-DUE trip-wire — $file:$line — date $iso has passed"
        violations=$((violations+1))
    fi
done < <(grep -rEn "$pattern" agent-manager/src/ 2>/dev/null || true)

[ "$violations" -gt 0 ] && exit 1 || exit 0
