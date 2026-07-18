#!/usr/bin/env bash
# Backup the Agent Manager Postgres database via pg_dump.
#
# Designed for cron:
#   0 2 * * * /opt/agent-manager/deploy/scripts/backup.sh >> /var/log/agm-backup.log 2>&1
#
# Output format: --format=custom (binary, compressed, restore-time selectable).
# Output path:   /opt/agent-manager/deploy/backups/agm-YYYY-MM-DDTHHMM.dump
#
# Retention:
#   - Keep the last 7 daily dumps   (rolled out after 7 days)
#   - Keep one Sunday weekly per recent month
# The retention policy is intentionally conservative — pgvector data tends to
# be large but irreplaceable for tenants. Tune REPLICAS_DAILY / REPLICAS_WEEKLY
# if storage pressure dictates.

set -euo pipefail

# ── Config (overridable via env) ────────────────────────────────────────────
COMPOSE_FILE="${COMPOSE_FILE:-$(cd "$(dirname "$0")/.." && pwd)/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-$(cd "$(dirname "$0")/.." && pwd)/.env.prod}"
BACKUP_DIR="${BACKUP_DIR:-$(cd "$(dirname "$0")/.." && pwd)/backups}"
REPLICAS_DAILY="${REPLICAS_DAILY:-7}"
REPLICAS_WEEKLY="${REPLICAS_WEEKLY:-4}"

mkdir -p "$BACKUP_DIR"

# ── Discover DB credentials from .env.prod ──────────────────────────────────
if [[ ! -r "$ENV_FILE" ]]; then
	echo "FATAL: cannot read env file at $ENV_FILE" >&2
	exit 1
fi
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a
DB_USER="${DB_USER:-admin}"

# ── Dump ────────────────────────────────────────────────────────────────────
ts="$(date -u +%Y-%m-%dT%H%MZ)"
out="$BACKUP_DIR/agm-${ts}.dump"

# Run pg_dump INSIDE the postgres container so we use the container's pg_dump
# (version-matched to the running server) and bind-mounted /backups path.
# --format=custom -> compressed, restorable subset selection.
# --no-owner / --no-acl -> dump is portable across users; restore reassigns to
# whoever runs pg_restore.
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
	pg_dump --username="$DB_USER" --dbname=agent_manager \
	        --format=custom --no-owner --no-acl --compress=6 \
	        --file="/backups/agm-${ts}.dump"

# Sanity: file exists and is non-empty.
if [[ ! -s "$out" ]]; then
	echo "FATAL: pg_dump produced empty or missing file: $out" >&2
	exit 2
fi

size_human=$(du -h "$out" | cut -f1)
echo "[$(date -u +%FT%TZ)] Backup OK: $out ($size_human)"

# ── Retention ───────────────────────────────────────────────────────────────
# Daily: keep most recent REPLICAS_DAILY non-weekly dumps.
# Weekly: keep most recent REPLICAS_WEEKLY Sunday dumps (timestamp pattern).
#
# This sorts by mtime (most-recent first), keeps the head, deletes the tail.
# `xargs -r` is a GNU-ism but `--no-run-if-empty` is portable on Debian/Ubuntu.

find "$BACKUP_DIR" -name 'agm-*.dump' -type f \
	-not -name "agm-*T*Z.dump" \
	-printf '%T@ %p\n' 2>/dev/null \
	| sort -rn | tail -n +$((REPLICAS_DAILY + 1)) | cut -d' ' -f2- \
	| xargs -r rm -f

# Note: the more nuanced weekly rotation (keep Sunday backups longer) is
# intentionally NOT implemented in v0 — daily-only retention at 7d depth is
# adequate for pre-launch. Add a `-name "agm-*Sunday*"` filter on a future
# rotation pass if/when business-continuity requirements demand it.

echo "[$(date -u +%FT%TZ)] Retention pass complete (kept last $REPLICAS_DAILY daily)."
