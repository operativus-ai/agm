#!/bin/bash
TOKEN=$(./login.sh | grep -o 'ey[a-zA-Z0-9_-]*\.[a-zA-Z0-9_-]*\.[a-zA-Z0-9_-]*')
curl -X POST http://localhost:8080/api/agents/procurator_assistant/runs/stream \
-H "Content-Type: application/json" \
-H "Authorization: Bearer $TOKEN" \
-d '{"messages":[{"role":"user","content":"Tell me a joke about a developer"}]}' \
-N
