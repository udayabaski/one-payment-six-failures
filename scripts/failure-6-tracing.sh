#!/usr/bin/env bash
# Failure #6: payment latency jumps to eight seconds. Nobody knows why.
source "$(dirname "$0")/common.sh"
reset

TRACE_ID=$(openssl rand -hex 16)
pay() {
  curl -s -o /dev/null -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
    -H "Idempotency-Key: \"$1\"" -H 'Content-Type: application/json' \
    -H "traceparent: 00-$2-00f067aa0ba902b7-01" \
    -H 'baggage: client.app=ios-5.2,card.number=4111111111111111' -d "$PAYMENT" \
    -w "   HTTP %{http_code} in %{time_total}s\n"
}

step "The provider gets slow (8s)"
curl -s -X PUT "$BASE/simulation/provider" -H 'Content-Type: application/json' \
  -d '{"latencyMs":7960,"unavailable":false}' >/dev/null
pay f6-a "$TRACE_ID"
sleep 1
step "The trace shows where the time went"
curl -s "$BASE/simulation/traces/$TRACE_ID"

reset
TRACE_ID=$(openssl rand -hex 16)
step "Same request, but the provider call and the event do not propagate trace context"
curl -s -X PUT "$BASE/simulation/provider" -H 'Content-Type: application/json' \
  -d '{"latencyMs":2000,"unavailable":false}' >/dev/null
curl -s -X PUT "$BASE/simulation/tracing" -H 'Content-Type: application/json' \
  -d '{"propagateOverHttp":false,"propagateOverMessaging":false}' >/dev/null
pay f6-b "$TRACE_ID"
sleep 1
curl -s "$BASE/simulation/traces/$TRACE_ID"
note "Our client span still says 'the provider call took 2s', but the provider's own span"
note "and the notification are gone from this trace: each landed in a disconnected trace."
note "If the slowness were inside the provider (its database, a queue), you could not see it."
