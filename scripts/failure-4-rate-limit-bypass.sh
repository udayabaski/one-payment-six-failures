#!/usr/bin/env bash
# Failure #4: the rate limiter is working - but the attacker walks around it.
source "$(dirname "$0")/common.sh"
reset

guess() {
  local path=$1 i=$2
  curl -s -o /dev/null -D - -X POST "$BASE$path" -H "X-Forwarded-For: 192.0.2.$((10 + i))" \
    -H 'Content-Type: application/json' -d '{"username":"alice","password":"guess"}' \
    | tr -d '\r' | awk -v i="$i" '/^HTTP/{s=$2} /^RateLimit:/{r=$2} /^X-Demo-Rate-Limit-Key:/{k=$2} END{printf "   guess %2d -> %s  bucket=%-16s RateLimit: %s\n", i, s, k, r}'
}

step "NAIVE: 10 guesses, a different X-Forwarded-For each time (limit is 5/min)"
for i in $(seq 1 10); do guess /naive/f4/login "$i"; done

step "FIXED: same 10 guesses. Your machine is not a trusted proxy, so the header is ignored"
for i in $(seq 1 10); do guess /api/login "$i"; done
