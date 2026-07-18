#!/bin/bash

# start-docker.sh
# Ensures the Docker daemon is running and starts all required Docker containers for the Agent Manager project.

echo "🐳 Checking if Docker daemon is running..."

if ! docker info > /dev/null 2>&1; then
    echo "⚠️ Docker is not running."
    
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo "🚀 Starting Docker Desktop for Mac..."
        open -a Docker
        
        echo "⏳ Waiting for Docker daemon to start (this may take a minute)..."
        while ! docker info > /dev/null 2>&1; do
            sleep 2
            echo -n "."
        done
        echo ""
        echo "✅ Docker daemon is now running."
    else
        echo "❌ Please start the Docker daemon manually, then run this script again."
        exit 1
    fi
else
    echo "✅ Docker daemon is already running."
fi

echo "🚀 Starting project Docker containers (PostgreSQL + pgAdmin)..."

# Navigate to the correct docker-compose.yml location
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
COMPOSE_DIR="$SCRIPT_DIR/../agent-manager/docker"

if [ -f "$COMPOSE_DIR/docker-compose.yml" ]; then
    cd "$COMPOSE_DIR" || exit 1
    docker-compose up -d
    echo "✅ All required Docker containers are up and running!"
else
    echo "❌ Could not find docker-compose.yml in $COMPOSE_DIR"
    exit 1
fi
