#!/bin/bash

# kill-backend.sh
# Safely targets and terminates running instances of the Agent Manager JVM
# while avoiding VSCode language servers and Gradle daemon processes.

echo "🛑 Terminating Agent Manager backend processes..."

# 1. Target the specific Main Class used by Spring Boot / VSCode Java Runner
if pgrep -f "AgentmanagerApplication" > /dev/null; then
    echo "🔪 Stopping main AgentmanagerApplication processes..."
    # Attempt graceful shutdown first
    pkill -15 -f "AgentmanagerApplication"
    sleep 2
    # Force kill any lingering processes
    pkill -9 -f "AgentmanagerApplication" 2>/dev/null
fi

# 2. Target the generic Spring Boot Maven Plugin if running via terminal (mvn spring-boot:run)
if pgrep -f "spring-boot:run" > /dev/null; then
    echo "🔪 Stopping Maven Spring Boot run processes..."
    pkill -9 -f "spring-boot:run" 2>/dev/null
fi

# 3. Clean up the default Spring Boot web port (Fallback)
# By default Spring Boot binds to 8080. If an orphaned child holds the port, nuke it.
PORT=8080
PID_ON_PORT=$(lsof -t -i:$PORT 2>/dev/null)
if [ ! -z "$PID_ON_PORT" ]; then
    echo "🔪 Killing orphaned process holding port $PORT (PID: $PID_ON_PORT)..."
    kill -9 $PID_ON_PORT 2>/dev/null
fi

echo "✅ All backend server processes have been thoroughly terminated."
