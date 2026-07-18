#!/bin/bash

# start-firecrawl.sh
# Downloads and spins up the official Firecrawl Open Source stack so the Agent Manager can scrape websites locally.

echo "🔥 Initializing Local Firecrawl Stack..."

# Set paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/.."
FIRECRAWL_DIR="$PROJECT_ROOT/firecrawl-local"

# Check if docker daemon is running
if ! docker info > /dev/null 2>&1; then
    echo "❌ Error: Docker is not running. Please start Docker Desktop or run ./scripts/start-docker.sh first."
    exit 1
fi

# Clone Firecrawl if it isn't already installed
if [ ! -d "$FIRECRAWL_DIR" ]; then
    echo "📥 Firecrawl repository not found. Downloading to $FIRECRAWL_DIR..."
    git clone https://github.com/mendableai/firecrawl.git "$FIRECRAWL_DIR"
    
    if [ $? -ne 0 ]; then
        echo "❌ Failed to download Firecrawl. Please check your internet connection."
        exit 1
    fi
else
    echo "✅ Firecrawl repository is already present."
fi

# Configure environment variables
cd "$FIRECRAWL_DIR" || exit 1
echo "⚙️ Configuring environment variables..."
if [ ! -f "apps/api/.env" ] && [ -f "apps/api/.env.example" ]; then
    cp apps/api/.env.example apps/api/.env
    # Force generic authentication settings for local testing (No Supabase required)
    echo "" >> apps/api/.env
    echo "USE_DB_AUTHENTICATION=false" >> apps/api/.env
    echo "✅ Created .env config for local usage."
fi

echo "🚀 Starting Firecrawl containers (Playwright, Redis, API, and Workers)..."
echo "⏳ Note: The first startup may take several minutes as it builds the Playwright image."

# Start the docker compose natively
if command -v docker-compose &> /dev/null; then
    docker-compose up -d
else
    docker compose up -d
fi

if [ $? -eq 0 ]; then
    echo "==========================================================="
    echo "✅ Firecrawl is spinning up!"
    echo "The API will be available at: http://localhost:3002"
    echo "==========================================================="
else
    echo "❌ Firecrawl containers failed to start."
    exit 1
fi
