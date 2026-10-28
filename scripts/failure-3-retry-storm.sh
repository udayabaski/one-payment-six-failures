#!/usr/bin/env bash
# Failure #3: ten thousand clients retry together.
source "$(dirname "$0")/common.sh"
reset

step "Provider down -> 503 + Retry-After"
curl -s -X PUT "$BASE/simulation/provider" -H 'Content-Type: application/json' \
  -d '{"latencyMs":150,"unavailable":true}' >/dev/null
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f3"' -H 'Content-Type: application/json' -d "$PAYMENT"
curl -s -X PUT "$BASE/simulation/provider" -H 'Content-Type: application/json' \
  -d '{"latencyMs":150,"unavailable":false}' >/dev/null

step "Simulating 10,000 clients against a 30s outage and 1,000 req/s of capacity"
curl -s "$BASE/simulation/retry-storm?clients=${CLIENTS:-10000}"
