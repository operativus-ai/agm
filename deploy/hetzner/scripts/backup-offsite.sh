#!/usr/bin/env bash
# Nightly Postgres backup → Hetzner Storage Box (offsite), with retention.
#
# Runs on the Hetzner app box (as deploy or root). Dumps the Postgres container,
# gzips it, copies it to a Storage Box over SSH (rsync), and prunes old dumps
# locally and remotely.
#
# Prereqs (one-time):
#   1. Order a Storage Box (Hetzner Robot → Storage Box). Note the username
#      (e.g. u123456) and host (u123456.your-storagebox.de).
#   2. Enable SSH on the box (Storage Box → Settings → SSH support).
#   3. Add this server's SSH public key to the Storage Box authorized_keys:
#        ssh-copy-id -p 23 u123456@u123456.your-storagebox.de
#      (Storage Box SSH listens on port 23.)
#   4. Configure the vars below (or export them) and install the cron entry:
#        sudo cp deploy/hetzner/scripts/backup-offsite.sh /usr/local/bin/agm-backup
#        sudo chmod +x /usr/local/bin/agm-backup
#        ( crontab -l 2>/dev/null; echo "30 3 * * * STORAGEBOX_USER=u123456 STORAGEBOX_HOST=u123456.your-storagebox.de /usr/local/bin/agm-backup >> /var/log/agm-backup.log 2>&1" ) | crontab -
set -euo pipefail

REPO_ROOT="${REPO_ROOT:-/opt/agent-manager}"
COMPOSE=(docker compose --env-file "$REPO_ROOT/deploy/.env.prod" -f "$REPO_ROOT/deploy/docker-compose.prod.yml")

DB_NAME="${DB_NAME:-agent_manager}"
DB_USER="${DB_USER:-admin}"
LOCAL_DIR="${LOCAL_DIR:-$REPO_ROOT/deploy/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"

# Storage Box (required for the offsite copy).
STORAGEBOX_USER="${STORAGEBOX_USER:?set STORAGEBOX_USER, e.g. u123456}"
STORAGEBOX_HOST="${STORAGEBOX_HOST:?set STORAGEBOX_HOST, e.g. u123456.your-storagebox.de}"
STORAGEBOX_PORT="${STORAGEBOX_PORT:-23}"
REMOTE_DIR="${REMOTE_DIR:-backups/agent-manager}"

ts="$(date -u +%Y%m%dT%H%M%SZ)"
file="agent_manager_${ts}.sql.gz"
mkdir -p "$LOCAL_DIR"

echo "==> Dumping $DB_NAME"
"${COMPOSE[@]}" exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" --no-owner --clean --if-exists \
  | gzip -9 > "$LOCAL_DIR/$file"
echo "    wrote $LOCAL_DIR/$file ($(du -h "$LOCAL_DIR/$file" | cut -f1))"

echo "==> Uploading to Storage Box"
# Ensure the remote dir exists, then rsync the new dump up.
ssh -p "$STORAGEBOX_PORT" "$STORAGEBOX_USER@$STORAGEBOX_HOST" "mkdir -p $REMOTE_DIR" 2>/dev/null || true
rsync -e "ssh -p $STORAGEBOX_PORT" -a "$LOCAL_DIR/$file" "$STORAGEBOX_USER@$STORAGEBOX_HOST:$REMOTE_DIR/"

echo "==> Pruning local dumps older than $KEEP_DAYS days"
find "$LOCAL_DIR" -name 'agent_manager_*.sql.gz' -mtime "+$KEEP_DAYS" -delete

echo "==> Pruning remote dumps older than $KEEP_DAYS days"
ssh -p "$STORAGEBOX_PORT" "$STORAGEBOX_USER@$STORAGEBOX_HOST" \
  "find $REMOTE_DIR -name 'agent_manager_*.sql.gz' -mtime +$KEEP_DAYS -delete" 2>/dev/null || \
  echo "    (remote prune skipped — Storage Box 'find' may be unavailable; manage retention via its snapshots)"

echo "==> Backup complete: $file"
