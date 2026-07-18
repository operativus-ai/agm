#!/bin/bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser2", "email": "test2@test.com", "password": "password", "role": ["ROLE_ADMIN"]}' > /dev/null

TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser2", "password": "password"}' | jq -r .token)

curl -N -X POST http://localhost:8080/api/agents/procurator_assistant/runs/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"message": "Explain quantum computing in 1 sentence", "stream": true}'
