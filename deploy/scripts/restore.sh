#!/usr/bin/env bash
# Restore the Agent Manager database from a pg_dump custom-format dump.
#
# Usage:
#   ./restore.sh /path/to/agm-YYYY-MM-DDTHHMMZ.dump
#
# What it does:
#   1. Stops the `app` service (no new writes during restore).
#   2. Drops and recreates the `agent_manager` database (clean slate — this is
#      a DESTRUCTIVE restore, not an additive merge).
#   3. pg_restore the dump.
#   4. Restarts the app service.
#   5. Prints a row-count summary for the largest tables so the operator can
#      eyeball-verify the restore worked.
#
# This is the disaster-recovery path. For partial / point-in-time recovery
# you would use a more sophisticated tool (e.g., wal-g) — out of scope for v0.

set -euo pipefail

DUMP_PATH="${1:-}"
if [[ -z "$DUMP_PATH" || ! -r "$DUMP_PATH" ]]; then
	echo "Usage: $0 /path/to/agm-*.dump" >&2
	echo "       (dump file must be readable)" >&2
	exit 1
fi

COMPOSE_FILE="${COMPOSE_FILE:-$(cd "$(dirname "$0")/.." && pwd)/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-$(cd "$(dirname "$0")/.." && pwd)/.env.prod}"
BACKUP_DIR="$(cd "$(dirname "$0")/.." && pwd)/backups"

if [[ ! -r "$ENV_FILE" ]]; then
	echo "FATAL: cannot read env file at $ENV_FILE" >&2
	exit 1
fi
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a
DB_USER="${DB_USER:-admin}"

# Ensure the dump is reachable from inside the postgres container (which has
# the host's ./backups bind-mounted at /backups).
DUMP_BASENAME="$(basename "$DUMP_PATH")"
if [[ "$(realpath "$DUMP_PATH")" != "$BACKUP_DIR"/* ]]; then
	echo "Staging dump into $BACKUP_DIR (postgres container only sees that path)..."
	cp "$DUMP_PATH" "$BACKUP_DIR/$DUMP_BASENAME"
fi

cat <<EOF
About to perform DESTRUCTIVE restore:
  Source dump:  $DUMP_PATH
  Target DB:    agent_manager (on the postgres service)
  Side effects: drops all current data, stops + restarts the app service.

Confirm by typing the word 'restore' (anything else aborts):
EOF
read -r confirm
[[ "$confirm" == "restore" ]] || { echo "Aborted."; exit 1; }

echo "[$(date -u +%FT%TZ)] Stopping app service..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" stop app

echo "[$(date -u +%FT%TZ)] Recreating database..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
	psql --username="$DB_USER" --dbname=postgres <<-SQL
	DROP DATABASE IF EXISTS agent_manager WITH (FORCE);
	CREATE DATABASE agent_manager OWNER "$DB_USER";
	SQL

echo "[$(date -u +%FT%TZ)] Restoring from /backups/$DUMP_BASENAME ..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
	pg_restore --username="$DB_USER" --dbname=agent_manager \
	           --no-owner --no-acl --exit-on-error \
	           "/backups/$DUMP_BASENAME"

echo "[$(date -u +%FT%TZ)] Row counts after restore:"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
	psql --username="$DB_USER" --dbname=agent_manager <<-SQL
	SELECT relname, n_live_tup
	FROM pg_stat_user_tables
	WHERE n_live_tup > 0
	ORDER BY n_live_tup DESC
	LIMIT 15;
	SQL

echo "[$(date -u +%FT%TZ)] Restarting app service..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d app

echo "[$(date -u +%FT%TZ)] Restore complete. Tail logs:"
echo "  docker compose --env-file $ENV_FILE -f $COMPOSE_FILE logs -f app"
