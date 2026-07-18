#!/bin/bash

# start-dev.sh
# Starts the backend (Docker) and frontend (Vite) for development.

echo "🚀 Starting Agent Manager Development Environment..."

# 1. Start Docker Containers
echo "🐳 Starting Backend Services (PostgreSQL + pgAdmin)..."
docker-compose up -d
echo "✅ Backend Services Started."

# 2. Check and Install Frontend Dependencies
echo "📦 Checking Frontend Dependencies..."
cd agent-manager-frontend
if [ ! -d "node_modules" ]; then
    echo "⚠️ node_modules not found. Installing dependencies..."
    npm install
fi

# 3. Start Frontend
echo "🎨 Starting Frontend Server..."
npm run dev
