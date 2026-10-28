#!/usr/bin/env bash
# Failure #2: the charge succeeds. The response is lost.
source "$(dirname "$0")/common.sh"
reset

step "NAIVE: arm a crash right after the provider charges the card"
curl -s -X POST "$BASE/simulation/faults/crash-after-provider-charge"
show -X POST "$BASE/naive/f2/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f2-naive"' -H 'Content-Type: application/json' -d "$PAYMENT"
step "NAIVE: the client retries with the same key"
show -X POST "$BASE/naive/f2/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f2-naive"' -H 'Content-Type: application/json' -d "$PAYMENT"
charges
note "One click, two charges."

reset
step "FIXED: same crash"
curl -s -X POST "$BASE/simulation/faults/crash-after-provider-charge"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f2-fixed"' -H 'Content-Type: application/json' -d "$PAYMENT"
charges

step "FIXED: immediate retry -> 409, the crashed worker still holds the lease"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f2-fixed"' -H 'Content-Type: application/json' -d "$PAYMENT"

note "waiting 6s for the lease (payments.idempotency.lease=5s) to expire..."
sleep 6
step "FIXED: retry after the lease -> recovery asks the provider, finds the charge, completes"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f2-fixed"' -H 'Content-Type: application/json' -d "$PAYMENT"

step "FIXED: another retry -> the stored response is replayed"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f2-fixed"' -H 'Content-Type: application/json' -d "$PAYMENT"
charges

step "FIXED: same key, different payload -> 422"
show -X POST "$BASE/api/payments" -H 'Authorization: Bearer token-user-a' \
  -H 'Idempotency-Key: "f2-fixed"' -H 'Content-Type: application/json' \
  -d '{"orderId":"ORDER-789","amount":2500,"currency":"USD"}'
