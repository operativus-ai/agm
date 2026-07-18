#!/usr/bin/env bash
# Phase-4 public-release export: builds a FRESH single-commit tree of AGM Core,
# excluding internal tooling and working docs, then runs a full gitleaks scan.
#
# Usage:   ./scripts/export-public.sh /path/to/export-dir
# Output:  an initialized git repo at export-dir with ONE commit and a clean
#          gitleaks report. Push it to the public remote ONLY after the flip
#          checklist (credential rotations, LICENSE text verification) is done:
#            cd <export-dir>
#            git remote add origin git@github.com:operativus-ai/agm.git
#            git push --force origin main        # replaces private history
set -euo pipefail

DEST="${1:?usage: export-public.sh <dest-dir>}"
SRC="$(cd "$(dirname "$0")/.." && pwd)"

# Internal-only paths that must never ship publicly. Everything else in the
# tracked tree ships. Review this list at every release.
EXCLUDES=(
  ".agent"                      # local agent config
  "CLAUDE.md"                   # AI-assistant project instructions
  "GEMINI.md"                   # AI-assistant project instructions
  ".claudeignore"
  ".cbmignore"
  "agent-manager.code-workspace"
  ".gitmodules"                 # firecrawl-local dev submodule pointer
  "firecrawl-local"
  "docs/analysis"               # internal audits / launch checklists
  "scripts/walkthroughs"        # demo walkthroughs (contain demo credentials)
  "scripts/export-public.sh"    # this script is release machinery, not product
)

rm -rf "$DEST"
mkdir -p "$DEST"
git -C "$SRC" archive HEAD | tar -x -C "$DEST"

for path in "${EXCLUDES[@]}"; do
  rm -rf "${DEST:?}/${path}"
done

cd "$DEST"
git init -q -b main
git add -A
git commit -q -m "AGM Core — initial public release" \
  --author="Operativus <engineering@operativus.ai>"

echo "Export: $(git rev-parse --short HEAD) at $DEST"
echo "Files: $(git ls-files | wc -l | tr -d ' ')"

if command -v gitleaks >/dev/null; then
  gitleaks detect --source . --no-banner --redact
else
  echo "WARNING: gitleaks not installed — secret scan skipped" >&2
  exit 2
fi
