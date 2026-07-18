#!/bin/bash
cd /Users/scottesker/Development/Projects/AI/agent-manager/agent-manager
export OPENAI_API_KEY=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
java -jar target/agent-manager-0.0.1-SNAPSHOT.jar > mytest.log 2>&1 &
APP_PID=$!
sleep 15
curl -N -X POST http://localhost:8080/api/agents/web_scraper/runs/stream -H 'Content-Type: application/json' -d '{"input": "scrape all of the documentation at site: https://docs.agno.com/agent-os/introduction"}'
sleep 3
kill -9 $APP_PID
cat mytest.log | grep -A 50 "Stream processing failed due to exception"
