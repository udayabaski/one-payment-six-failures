# One Payment, Six Failures

Runnable companion code for the article **"One Payment, Six Failures: the HTTP headers that help keep a payment correct, and where each one's guarantee stops."**

Every failure from the article is simulated twice:

- a **naive** endpoint (`/naive/...`) that looks reasonable and breaks;
- a **fixed** endpoint (`/api/...`) that uses the header *and* the mechanism behind it.

Each pair has an integration test that shows the difference, plus a curl script you can watch against the running app.

Built with **Java 25**, **Spring Boot 4.1 / WebFlux**, R2DBC and in-memory H2. Nothing to install except a JDK.

```bash
./mvnw test                                 # run all six simulations (about 30s)
./mvnw spring-boot:run                      # start the platform on :8080
./scripts/failure-2-idempotency.sh          # watch one scenario step by step
```

---

## The simulated platform

Everything runs in one process so you can step through it in a debugger, but each box from the article is a separate component, and the provider is called over **real HTTP**.

```
  client (tests / curl)
        │  Authorization, Idempotency-Key, traceparent, baggage, If-Match, X-Forwarded-For
        ▼
  TracingWebFilter ─▶ BearerAuthenticationFilter            "API gateway"
        │
        ▼
  OrderAccessPolicy                                           "Order service"
        │
        ▼
  PaymentService ──▶ IdempotencyStore / PaymentRepository     "Payment service" ─▶ H2 (R2DBC)
        │       └──▶ PaymentProviderClient ──HTTP──▶ /provider/v1/charges   "Payment provider" (fake)
        ▼
  PaymentEventPublisher ─▶ InMemoryEventBus ─▶ NotificationService          "Event queue" / "Notifications"
```

| # | Failure | Headers | Naive endpoint | Fixed endpoint | Test | Script |
|---|---|---|---|---|---|---|
| 1 | Valid token, unauthorized payment | `Authorization`, `WWW-Authenticate` | `POST /naive/f1/payments` | `POST /api/payments` | `Failure1AuthorizationTest` | `failure-1-authorization.sh` |
| 2 | Charge succeeds, response lost | `Idempotency-Key` | `POST /naive/f2/payments` | `POST /api/payments` | `Failure2IdempotencyTest` | `failure-2-idempotency.sh` |
| 3 | Retry storm | `Retry-After` | simulated `IMMEDIATE` / `RETRY_AFTER_ONLY` | `ResilientPaymentClient` | `Failure3RetryTest` | `failure-3-retry-storm.sh` |
| 4 | Rate-limit bypass | `X-Forwarded-For`, `Forwarded`, `RateLimit`, `RateLimit-Policy` | `POST /naive/f4/login` | `POST /api/login` | `Failure4RateLimitTest` | `failure-4-rate-limit-bypass.sh` |
| 5 | Conflicting refund updates | `ETag`, `If-Match` | `PATCH /naive/f5/refunds/{id}` (and `/check-then-act`) | `PATCH /api/refunds/{id}` | `Failure5ConcurrencyTest` | `failure-5-lost-update.sh` |
| 6 | Latency nobody can explain | `traceparent`, `tracestate`, `baggage` | propagation switched off | propagation on | `Failure6TracingTest` | `failure-6-tracing.sh` |

Run one scenario's test on its own with, for example, `./mvnw test -Dtest=Failure2IdempotencyTest`.

---

## Failure #1: the token is valid, the payment is still unauthorized

**Simulated:** `user-a` holds a perfectly valid token and uses it to pay for `ORDER-456`, which belongs to `user-b`.

- `BearerAuthenticationFilter` validates the token and returns `401` with an RFC 6750 challenge for missing, expired, revoked, unknown or wrong-audience tokens.
- `NaiveAuthorizationController` stops there and charges any order it's given.
- `OrderAccessPolicy` also checks the `payments:write` scope (`403` + `error="insufficient_scope"`) and **order ownership**.

```
NAIVE: user-a's valid token pays for ORDER-456   -> 201 Created  (charged by user-a, owned by user-b)
FIXED: same request against /api/payments        -> 403 Forbidden
```

Whether a foreign order returns `403` or `404` is a deliberate, configurable decision: `payments.authz.foreign-order-response=FORBIDDEN|NOT_FOUND`.

The test `stolenTokenStillWorks` documents a limit: a stolen bearer token works for whoever holds it. No header can detect that.

## Failure #2: the charge succeeds, the response is lost

**Simulated:** a fault injector "crashes" the payment service right after the provider charges the card and before the result is saved. The client then retries.

| | Naive | Fixed |
|---|---|---|
| Key storage | in-memory map, written *after* the charge | `idempotency_records` row inserted *before* the charge; the primary key makes the reservation atomic |
| Concurrent retries | check-then-act, both charge | the second `INSERT` loses: `409 Conflict` + `Retry-After` |
| Crash recovery | the key was never stored, so the retry charges again | lease expires, then the retry **asks the provider** by the persisted reference and completes the original payment |
| Provider idempotency | none | stable `providerRequestId` sent as the provider's own `Idempotency-Key` |
| Same key, different body | ignored | `422` (request fingerprint mismatch) |
| Provider timeout | n/a | `503 "Payment outcome unknown"`, and the next retry reconciles instead of charging again |

```
NAIVE  crash, retry        -> provider charges: 2
FIXED  crash               -> 500, provider charges: 1
       immediate retry     -> 409 Conflict, Retry-After: 1
       retry after lease   -> 201, same charge id (recovered from the provider)
       another retry       -> 201, Idempotent-Replayed: true
                              provider charges: 1
```

Read `PaymentService` top to bottom; the flow is drawn in its Javadoc. `IdempotencyStore` shows that every state change is a single conditional SQL statement.

## Failure #3: ten thousand clients retry together

Two parts:

1. **Server:** when the provider is down, `/api/payments` answers `503` + `Retry-After` and releases the idempotency key, so a later retry with the same key can proceed.
2. **Client:** `ResilientPaymentClient` treats `Retry-After` as a *minimum*, adds exponential backoff and jitter, and enforces a retry budget and a deadline. It **never replays a payment that has no idempotency key**.

`RetryStormSimulator` runs 10,000 clients in virtual time against a 30-second outage, after which the service can accept 1,000 requests per second:

```
IMMEDIATE (retry every second, ignore Retry-After)
peak while down=10,000 req/s  peak after recovery=0 req/s
attempts=100,000  succeeded=0  gave up=10,000           <- budget burned before recovery

RETRY_AFTER_ONLY (wait exactly Retry-After)
peak while down=10,000 req/s  peak after recovery=10,000 req/s
attempts=64,000  succeeded=9,000  gave up=1,000  last success: t=270s
  t= 30- 39s   10,000 ##################################################
  t= 60- 69s    9,000 #############################################
  t= 90- 99s    8,000 ########################################   ... every 30s

RETRY_AFTER_WITH_BACKOFF_AND_JITTER
peak while down=10,000 req/s  peak after recovery=383 req/s
attempts=20,000  succeeded=10,000  gave up=0  last success: t=60s
  t= 30- 39s    3,100 ################
  t= 40- 49s    3,431 #################
  t= 50- 59s    3,278 ################
```

Try other sizes: `curl 'localhost:8080/simulation/retry-storm?clients=50000'`.

## Failure #4: the rate limiter works, the attacker walks around it

**Simulated:** password guessing against `/login`, limited to 5 attempts per minute per client IP. The attacker sends a different `X-Forwarded-For` with every request.

- `NaiveClientIpResolver` trusts the leftmost `X-Forwarded-For` entry, so every guess gets a fresh bucket.
- `TrustedProxyClientIpResolver` ignores forwarding headers unless the TCP peer is a configured proxy (`payments.proxy.trusted-proxies`, default `10.0.0.0/8`). When the peer is a trusted proxy, it walks the chain **from the right** and stops at the first address that isn't one of ours. It parses `Forwarded` (RFC 7239) as well as `X-Forwarded-For`, and never does DNS lookups on header values.

```
NAIVE  guess  1..10 -> 401  bucket=ip:192.0.2.11, .12, .13 ...   RateLimit: "login";r=4;t=60
FIXED  guess  1..5  -> 401  bucket=ip:0:0:0:0:0:0:0:1            RateLimit: "login";r=4..0
       guess  6..10 -> 429  Retry-After                          RateLimit: "login";r=0;t=60
```

Both endpoints send the same `RateLimit` / `RateLimit-Policy` headers. The headers only *describe* the limit; `FixedWindowRateLimiter` enforces it. The `X-Demo-Rate-Limit-Key` header exists only so you can see which bucket was used. Don't echo it in a real API.

## Failure #5: two operators issue conflicting refund updates

**Simulated:** refund `REF-123` (`$100`, `PENDING`, version 7). Operator A approves, operator B rejects from a stale screen.

| Endpoint | Behaviour |
|---|---|
| `PATCH /naive/f5/refunds/REF-123` | ignores `If-Match`: last write wins, A's approval silently disappears |
| `PATCH /naive/f5/refunds/REF-123/check-then-act` | checks `If-Match` in Java, then runs `UPDATE` without a version predicate. Two simultaneous requests **both** succeed |
| `PATCH /api/refunds/REF-123` | missing `If-Match` → `428`; stale → `412` with the current `ETag`; `UPDATE ... WHERE id = ? AND version = ?` makes check and write atomic; invalid state transition → `409` |

```
FIXED  A  If-Match: "refund-v7"  -> 200, ETag: "refund-v8"
       B  If-Match: "refund-v7"  -> 412 Precondition Failed, ETag: "refund-v8"
       simultaneous A + B        -> one 200, one 412
NAIVE  simultaneous A + B        -> 200, 200
```

## Failure #6: payment latency jumps to eight seconds

**Simulated:** the provider becomes slow. Every hop creates spans and propagates W3C trace context over HTTP and through the message headers on the event bus.

```
[payments-api] POST /api/payments                                                7,976 ms
 +-- [payments-api] api-gateway authenticate                                         0 ms
 +-- [payments-api] order-service authorize-order                                    1 ms
 +-- [payments-api] payment-service process-payment                              7,976 ms
      +-- [database] INSERT idempotency_records                                      1 ms
      +-- [payments-api] payment-provider POST /v1/charges                       7,975 ms
      |    +-- [payment-provider] POST /provider/v1/charges                      7,967 ms
      +-- [database] INSERT payments                                                 4 ms
      +-- [database] UPDATE idempotency_records (complete)                           3 ms
      +-- [payments-api] publish payment.completed                                   1 ms
           +-- [notification-service] consume payment.completed  (async)           302 ms
```

The tests then break propagation on purpose:

- **No `traceparent` on the provider call:** the provider's span lands in a separate trace.
- **No headers on the message:** the notification becomes an orphan trace.
- **Malformed incoming `traceparent`:** rejected, and a new trace is started.
- **`baggage` with `card.number` / `user.email`:** dropped by an allowlist (`payments.tracing.baggage-allowlist`), never forwarded.
- **The async notification** finishes *after* the HTTP response, so it is not counted as request latency.

The tracer in `tracing/` is deliberately tiny so the mechanics are visible. In production use OpenTelemetry or Micrometer Tracing; the concepts map one to one.

---

## Running the demos

```bash
./mvnw spring-boot:run
# in another terminal
./scripts/failure-1-authorization.sh
./scripts/failure-2-idempotency.sh       # waits ~6s for a lease to expire
./scripts/failure-3-retry-storm.sh
./scripts/failure-4-rate-limit-bypass.sh
./scripts/failure-5-lost-update.sh
./scripts/failure-6-tracing.sh           # takes ~10s: the provider is slowed to 8s
```

Port 8080 busy? Run `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=18080` and `export BASE=http://localhost:18080`.

### Seed data

| Token | Who | Notes |
|---|---|---|
| `token-user-a` | user-a | owns `ORDER-123` ($100) and `ORDER-789` ($25) |
| `token-user-b` | user-b | owns `ORDER-456` ($100) |
| `token-operator-a`, `token-operator-b` | support operators | for refunds |
| `token-read-only` | user-a | only `payments:read` |
| `token-expired`, `token-revoked`, `token-wrong-audience` | user-a | always rejected with `401` |

Login: `alice` / `correct-horse-battery-staple`. Refund: `REF-123`, `PENDING`, version 7.

### Simulation control API

Not part of any real payment API; it exists so the scripts can inject faults.

| Call | Effect |
|---|---|
| `POST /simulation/reset` | wipe all state, re-seed `REF-123` |
| `PUT /simulation/provider` `{"latencyMs":8000,"unavailable":false}` | slow down or take down the provider |
| `GET /simulation/provider/charges` | every charge the provider made (that is, every time the card was charged) |
| `POST /simulation/faults/crash-after-provider-charge?times=1` | crash after the next charge, before saving it |
| `PUT /simulation/tracing` `{"propagateOverHttp":false,"propagateOverMessaging":true}` | break context propagation |
| `GET /simulation/traces/{traceId}` | render a trace tree (every response has an `X-Trace-Id` header) |
| `GET /simulation/retry-storm?clients=10000&strategy=RETRY_AFTER_ONLY` | run the retry storm model |

---

## What is simplified (on purpose)

This repo is meant for learning, not for copying straight into production.

- One process stands in for several services; the provider is reached over HTTP to keep that boundary real.
- H2 in memory; the SQL (`INSERT` on a primary key, conditional `UPDATE ... WHERE version = ?`) carries over to PostgreSQL and MySQL.
- The event is published **after** the database commit. A crash in between loses it; production systems use a transactional outbox.
- Idempotency records have an `expires_at` column, but no cleanup job runs.
- The rate limiter and the tracer are in memory and single-node.
- Tokens are opaque strings in a map instead of JWTs or introspection, so the curl commands stay readable.
- `/simulation/**` and `/provider/**` are unauthenticated.

## Project layout

```
src/main/java/com/example/onepayment/
├── auth/          Failure #1  bearer filter, scope + ownership policy, naive controller
├── payments/      Failure #2  idempotent payment flow, durable records, naive controller
├── retry/         Failure #3  Retry-After parsing, retry policy, resilient client, storm simulator
├── ratelimit/     Failure #4  trusted-proxy IP resolution, limiter, login endpoints
├── refunds/       Failure #5  ETag / If-Match, atomic version check, naive controllers
├── tracing/       Failure #6  traceparent / baggage, tiny tracer, in-memory "backend"
├── events/        event bus, publisher (injects context), notification consumer
├── provider/      fake external payment provider + our HTTP client for it
├── orders/        seeded orders
└── simulation/    reset, fault injection, control endpoints
```

## References

- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html)
- [RFC 6750: OAuth 2.0 Bearer Token Usage](https://www.rfc-editor.org/rfc/rfc6750.html)
- [The Idempotency-Key HTTP Header Field (IETF draft)](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [RateLimit header fields for HTTP (IETF draft)](https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/)
- [RFC 7239: Forwarded HTTP Extension](https://www.rfc-editor.org/rfc/rfc7239.html)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/) and [W3C Baggage](https://www.w3.org/TR/baggage/)
