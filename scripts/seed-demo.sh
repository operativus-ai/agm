#!/usr/bin/env bash
# Seed/refresh demo data, then optionally boot the BE under the demo profile.
# Idempotent — safe to re-run.
#
# As of the application-demo.properties change that removed
# spring.liquibase.contexts=demo, the demo profile no longer auto-applies the
# demo changesets on boot. This script runs Liquibase explicitly first, then
# boots the BE under the demo profile so the demo data + demo config are both
# present together.
#
# Usage:
#   ./scripts/seed-demo.sh             # apply demo data, then boot the BE
#   ./scripts/seed-demo.sh --liquibase # apply demo data only, do not start BE

set -euo pipefail
cd "$(dirname "$0")/.."

echo ">> Bringing up Postgres..."
docker-compose up -d postgres

echo ">> Waiting for Postgres to be ready..."
until docker-compose exec -T postgres pg_isready -U agentmanager > /dev/null 2>&1; do
    sleep 1
done
echo ">> Postgres is ready."

cd agent-manager

echo ">> Applying Liquibase demo changesets..."
./mvnw liquibase:update -Dliquibase.contexts=demo

if [ "${1:-}" = "--liquibase" ]; then
    echo ">> Demo data applied. BE is not running."
else
    echo ">> Starting BE with demo profile..."
    SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
fi
