#!/usr/bin/env bash
# Failure #5: two operators issue conflicting refund updates.
source "$(dirname "$0")/common.sh"
reset

patch() { # path token if-match status
  local args=(-X PATCH "$BASE$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -d "{\"status\":\"$4\"}")
  [ -n "$3" ] && args+=(-H "If-Match: $3")
  show "${args[@]}"
}

step "Both operators open REF-123"
show "$BASE/api/refunds/REF-123" -H 'Authorization: Bearer token-operator-a'

step "NAIVE: A approves, B (stale screen) rejects. Last write wins."
patch /naive/f5/refunds/REF-123 token-operator-a "" APPROVED
patch /naive/f5/refunds/REF-123 token-operator-b "" REJECTED
note "A's approval is gone, and nobody was told."

reset
step "FIXED: A approves with If-Match \"refund-v7\""
patch /api/refunds/REF-123 token-operator-a '"refund-v7"' APPROVED
step "FIXED: B tries with the stale If-Match \"refund-v7\" -> 412"
patch /api/refunds/REF-123 token-operator-b '"refund-v7"' REJECTED
step "FIXED: no If-Match at all -> 428"
patch /api/refunds/REF-123 token-operator-b "" REJECTED

reset
step "NAIVE check-then-act: two simultaneous PATCHes, both with If-Match \"refund-v7\""
patch /naive/f5/refunds/REF-123/check-then-act token-operator-a '"refund-v7"' APPROVED &
patch /naive/f5/refunds/REF-123/check-then-act token-operator-b '"refund-v7"' REJECTED &
wait
note "Both got 200: the version was checked in Java, not in the UPDATE."
