#!/usr/bin/env bash
# Shared helpers for the demo scripts. Start the app first: ./mvnw spring-boot:run
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
PAYMENT='{"orderId":"ORDER-123","amount":10000,"currency":"USD"}'

step() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
note() { printf '\033[0;33m   %s\033[0m\n' "$*"; }

reset() { curl -s -X POST "$BASE/simulation/reset" >/dev/null; }

# Prints the status line, the interesting headers, and the body.
show() {
  curl -s -i "$@" | tr -d '\r' | grep -E -i '^(HTTP/|www-authenticate|retry-after|idempotent-replayed|etag|ratelimit|x-demo|x-trace-id)|^\{|^\[' || true
}

charges() {
  local n
  n=$(curl -s "$BASE/simulation/provider/charges" | grep -o '"chargeId"' | wc -l | tr -d ' ')
  note "provider charges so far: $n"
}
