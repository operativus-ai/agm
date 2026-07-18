#!/usr/bin/env bash
# Wipe demo rows from the database, leaving schema and production seed untouched.
# Triggers demo-099-wipe.sql via the "demo-wipe" Liquibase context.
#
# Usage:
#   ./scripts/reset-demo.sh           # wipe only
#   ./scripts/reset-demo.sh --reseed  # wipe then reseed in the same run

set -euo pipefail
cd "$(dirname "$0")/.."

echo ">> Ensuring Postgres is up..."
docker-compose up -d postgres
until docker-compose exec -T postgres pg_isready -U agentmanager > /dev/null 2>&1; do
    sleep 1
done

cd agent-manager

if [ "${1:-}" = "--reseed" ]; then
    echo ">> Wiping + reseeding demo data..."
    ./mvnw liquibase:update -Dliquibase.contexts=demo-wipe,demo
else
    echo ">> Wiping demo data..."
    ./mvnw liquibase:update -Dliquibase.contexts=demo-wipe
fi

echo ">> Done."
