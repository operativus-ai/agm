#!/bin/bash

# --- CONFIGURATION ---
CONTAINER_NAME="my_db"
DB_NAME="gettabuzz"
DB_USER="admin"
# Using single quotes to prevent '!' or '#' from being interpreted by the shell
DB_PASS='!Yamaha#69Gto' 
CHANGELOG_DIR="./src/main/resources/db/changelog"
MASTER_CHANGELOG="$CHANGELOG_DIR/db.changelog-master.xml"

echo "🚀 Starting Destructive Reset for $DB_NAME..."

# 1. Kill existing container and its data
echo "⏹️  Removing old container..."
docker rm -f "$CONTAINER_NAME" 2>/dev/null

# 2. Wipe Liquibase Changelogs
echo "📂 Cleaning Liquibase files..."
mkdir -p "$CHANGELOG_DIR"
rm -rf "$CHANGELOG_DIR"/*

# 3. Create fresh Master Changelog
echo "📝 Creating new master changelog..."
cat <<EOF > "$MASTER_CHANGELOG"
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <include file="db.changelog-1.0.xml" relativeToChangelogFile="true"/>
</databaseChangeLog>
EOF

# 4. Start fresh Postgres container
# We wrap environment variables in single quotes to protect special characters
echo "⬆️  Launching Postgres 17-alpine..."
docker run -d \
  --name "$CONTAINER_NAME" \
  -e POSTGRES_DB="$DB_NAME" \
  -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_PASSWORD="$DB_PASS" \
  -p 5432:5432 \
  postgres:17-alpine

# 5. Wait for Readiness
echo "⏳ Waiting for $DB_NAME to be ready..."
until docker exec "$CONTAINER_NAME" pg_isready -U "$DB_USER" -d "$DB_NAME" > /dev/null 2>&1; do
  sleep 1
done

echo "✅ Database is clean and running at localhost:5432"
echo "👤 User: $DB_USER | DB: $DB_NAME"