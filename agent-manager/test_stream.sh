#!/bin/bash
RESPONSE=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"testusr","password":"password123"}')
TOKEN=$(echo $RESPONSE | grep -o '"token":"[^"]*' | cut -d'"' -f4)

curl -N -X POST http://localhost:8080/api/agents/agno_assist/runs/stream \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"message":"Say hello strictly","stream":true,"generateFollowups":false}'
