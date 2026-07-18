#!/usr/bin/env bash
# Pull-based deploy for the Agent Manager single-VM stack (GHCR images).
#
# Invoked by the release.yml self-hosted "deploy" job, or run by hand on the box:
#   IMAGE_TAG=<short-sha> [GHCR_USER=<u> GHCR_TOKEN=<t>] \
#     [EXTRA_COMPOSE_FILES="deploy/docker-compose.firecrawl.yml"] \
#     deploy/hetzner/scripts/deploy-pull.sh
#
# Unlike deploy.sh (build-on-server), this pulls prebuilt images from GHCR. The
# box never compiles. EXTRA_COMPOSE_FILES appends optional overlays (e.g. a
# co-located Firecrawl stack) as additional `-f` files, in order.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

: "${IMAGE_TAG:?set IMAGE_TAG to the image tag to deploy (e.g. a short SHA)}"

if [[ ! -f deploy/.env.prod ]]; then
  echo "ERROR: deploy/.env.prod is missing on the box." >&2
  exit 1
fi

# Base compose + any overlays (space-separated paths in EXTRA_COMPOSE_FILES).
COMPOSE=(docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml)
for f in ${EXTRA_COMPOSE_FILES:-}; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: EXTRA_COMPOSE_FILES entry not found: $f" >&2
    exit 1
  fi
  COMPOSE+=(-f "$f")
done

# Sync compose files / scripts to the commit being deployed (NOT a source build).
echo "==> Syncing repo to origin/main"
git fetch --quiet origin main
git reset --hard --quiet origin/main

# Authenticate to GHCR for private packages (ephemeral token from the workflow).
if [[ -n "${GHCR_TOKEN:-}" ]]; then
  echo "==> docker login ghcr.io"
  echo "$GHCR_TOKEN" | docker login ghcr.io -u "${GHCR_USER:-x-access-token}" --password-stdin
fi

echo "==> Pulling images at tag $IMAGE_TAG"
IMAGE_TAG="$IMAGE_TAG" "${COMPOSE[@]}" pull

echo "==> Starting / updating containers"
IMAGE_TAG="$IMAGE_TAG" "${COMPOSE[@]}" up -d --remove-orphans

echo "==> Waiting for app health (Liquibase + pool warmup)"
for i in $(seq 1 40); do
  status="$(IMAGE_TAG="$IMAGE_TAG" "${COMPOSE[@]}" ps --format json app 2>/dev/null | jq -r '.Health // .State' 2>/dev/null || echo '')"
  if [[ "$status" == "healthy" ]]; then echo "    app healthy"; break; fi
  if [[ "$i" == "40" ]]; then
    echo "ERROR: app did not become healthy in time. Recent logs:" >&2
    IMAGE_TAG="$IMAGE_TAG" "${COMPOSE[@]}" logs --tail=50 app >&2
    exit 1
  fi
  sleep 6
done

echo "==> Pruning dangling images"
docker image prune -f >/dev/null

echo "==> Deployed tag $IMAGE_TAG. Running services:"
IMAGE_TAG="$IMAGE_TAG" "${COMPOSE[@]}" ps
