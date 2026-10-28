#!/usr/bin/env bash
# Failure #1: the token is valid. The payment is still unauthorized.
source "$(dirname "$0")/common.sh"
reset

step "Expired token -> 401 with a WWW-Authenticate challenge"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-expired' \
  -H 'Idempotency-Key: "f1-a"' -H 'Content-Type: application/json' -d "$PAYMENT"

step "Read-only token -> 403 insufficient_scope"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-read-only' \
  -H 'Idempotency-Key: "f1-b"' -H 'Content-Type: application/json' -d "$PAYMENT"

step "NAIVE: user-a's valid token pays for ORDER-456, which belongs to user-b"
show -X POST "$BASE/naive/f1/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Content-Type: application/json' -d '{"orderId":"ORDER-456","amount":10000,"currency":"USD"}'
charges

reset
step "FIXED: same request against /api/payments -> 403"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f1-c"' -H 'Content-Type: application/json' \
  -d '{"orderId":"ORDER-456","amount":10000,"currency":"USD"}'
charges
note "Authentication said WHO. Only the ownership check said WHAT they may pay for."
