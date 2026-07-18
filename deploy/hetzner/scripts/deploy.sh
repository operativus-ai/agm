#!/usr/bin/env bash
# Build-on-server deploy / update for the Agent Manager single-VM stack.
#
# Run on the Hetzner box as the deploy user, from the repo root:
#   cd /opt/agent-manager && deploy/hetzner/scripts/deploy.sh
#
# What it does: pull latest source, (re)build images, recreate containers,
# wait for the app to report healthy, then prune dangling images. Liquibase
# migrations run automatically inside the app container on boot.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

COMPOSE=(docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml)
BRANCH="${DEPLOY_BRANCH:-main}"

if [[ ! -f deploy/.env.prod ]]; then
  echo "ERROR: deploy/.env.prod is missing. Copy deploy/.env.prod.example, fill it in, chmod 600." >&2
  exit 1
fi

echo "==> Pulling $BRANCH"
git fetch --quiet origin "$BRANCH"
git checkout --quiet "$BRANCH"
git reset --hard --quiet "origin/$BRANCH"

echo "==> Building images (this can take several minutes on first build)"
"${COMPOSE[@]}" build

echo "==> Starting / updating containers"
"${COMPOSE[@]}" up -d --remove-orphans

echo "==> Waiting for app health (Liquibase + pool warmup)"
for i in $(seq 1 40); do
  status="$("${COMPOSE[@]}" ps --format json app 2>/dev/null | jq -r '.Health // .State' 2>/dev/null || echo '')"
  if [[ "$status" == "healthy" ]]; then echo "    app healthy"; break; fi
  if [[ "$i" == "40" ]]; then
    echo "ERROR: app did not become healthy in time. Recent logs:" >&2
    "${COMPOSE[@]}" logs --tail=50 app >&2
    exit 1
  fi
  sleep 6
done

echo "==> Pruning dangling images"
docker image prune -f >/dev/null

echo "==> Done. Running services:"
"${COMPOSE[@]}" ps
