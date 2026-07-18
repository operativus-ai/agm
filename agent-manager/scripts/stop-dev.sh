#!/bin/bash
PID=$(lsof -ti:8080)

if [ -z "$PID" ]; then
    echo "❌ No backend process found on port 8080."
else
    kill -9 $PID
    echo "✅ Backend (PID $PID) stopped."
fi
