# Seat Reservation at Scale

A JSON API that sells assigned seats for a show and stays correct under an on-sale stampede: a seat is never sold twice, a user never exceeds the per-user limit, a retried request never reserves twice, and overload is a clean 4xx, never a 5xx.

Java 21 · Spring Boot 3.5 · MySQL 8.4 (InnoDB) · plain JDBC (`JdbcTemplate`) · Flyway · Micrometer/Prometheus · Docker.

- **Design** (schema, API contract, concurrency, every decision): [DESIGN.md](DESIGN.md)
- **Write-up** (atomic decision, idempotency, partitions, on-call, AI usage): [WRITEUP.md](WRITEUP.md)
- **AI log** (milestone by milestone, what the AI produced and what I decided): [AI_LOG.md](AI_LOG.md)

---

## Live service

| | |
|---|---|
| Base URL | **https://seat-reservation-production-b408.up.railway.app** |
| Liveness | [`/actuator/health/liveness`](https://seat-reservation-production-b408.up.railway.app/actuator/health/liveness) |
| Readiness (checks the database, fails closed) | [`/actuator/health/readiness`](https://seat-reservation-production-b408.up.railway.app/actuator/health/readiness) |
| Prometheus metrics | [`/actuator/prometheus`](https://seat-reservation-production-b408.up.railway.app/actuator/prometheus) |
| Admin secret (needed to create shows) | `ee604549ef0223cda68ba9875ca0a38e` |
| Live logs during a burst (screen recording) | [Google Drive](https://drive.google.com/file/d/1dmNx2Fx_7Pgz0AM4Sbrp8966EhI9Fp1F/view?usp=sharing) |

Hosting is free tier: the app on Railway (Southeast Asia), MySQL on Aiven (DigitalOcean, Bengaluru), connected over TLS. Every database statement crosses that network hop, which sets the throughput ceiling; see [Results](#burst-results-against-the-live-url).

---

## Run the burst (one command)

```sh
ADMIN_SECRET=ee604549ef0223cda68ba9875ca0a38e ./burst.sh https://seat-reservation-production-b408.up.railway.app
```

It needs a JDK 21+ on the path; if there isn't one, the script runs the same program in the `eclipse-temurin:21-jdk` Docker image. Against a local stack: `./burst.sh http://localhost:8080` (the local admin secret is the default).

What it does ([burst/Burst.java](burst/Burst.java), one file, JDK only):

1. Waits for readiness (up to 3 minutes, so a cold start doesn't fail the run), creates a fresh 1,000-seat show with limit 4, and mints tokens for 3,000 users.
2. **Hot-seat storm:** 500 different users grab each of 5 seats at the same instant (2,500 requests).
3. **Stampede:** the rest of the 20,000 requests, half aimed at a block of 50 "good" seats, 1–2 seats each. About 10% are resent with the same idempotency key (client retries) and 2% reuse a key for different seats.
4. **Per-user limit:** one user fires 10 parallel single-seat reserves on the limit-4 show.
5. Prints the outcome distribution and PASS/FAIL for every property below, and exits 0 on PASS, 1 on FAIL.

Checks: zero 5xx · each hot seat exactly one 201 and everyone else 409 · no seat in two 201 responses · one reservation per idempotency key · at most 4 seats per user · `available + held + confirmed == total` in snapshots taken during the stampede and at the end · confirmed seats == seats in 201 responses · every Prometheus counter delta == the observed responses · `seats_available` gauge == API.

Options: `--requests 20000 --users 3000 --seats 1000 --hot-seats 5 --storm 500 --concurrency 1000 --limit 4 --admin-secret …`. All requests are released at once; `--concurrency` caps how many are in flight from one machine.

### Burst results against the live URL

Latest run (20,000 requests): **RESULT: PASS**

```
Hot-seat storm    2500 requests in   8.82s  (  283 req/s, p50 2527 ms, p99  7068 ms)
Stampede         17490 requests in  44.95s  (  389 req/s, p50 1927 ms, p99  9373 ms)

  200  idempotent-replay          95
  201  confirmed                 942
  409  idempotency-key-reused      7
  409  per-user-limit              6
  409  seat-taken              18867
  429  overloaded                 83
  5xx                              0

Hot seats A12–A16: each 1 × 201, 499 × 409
Final: available 9 + held 0 + confirmed 991 = 1000
Metric deltas == observed responses for every counter
```

How it got there (every run: zero 5xx, no seat sold twice, invariant held):

| Run | Change | req/s | 429s | Hot seats passing |
|---|---|---|---|---|
| 1 | first deploy, pool 30 | 150 | 2,657 | 1/5 |
| 2 | pool 50; show lookup + seat check in one statement | 250 | 803 | 0/5 |
| 3 | single-seat requests skip the explicit row lock | 265 | 264 | 2/5 |
| 4 | pre-check incl. idempotency key in one statement | 389 | 83 | 5/5 |

The same burst against the local Docker stack (database on the same machine): ~2,200–5,000 req/s, zero 429s.

---

## Run it locally

Requires Docker.

```sh
docker compose up --build        # MySQL 8.4 + the app on http://localhost:8080
./burst.sh http://localhost:8080 # the stampede, against the local stack
./mvnw verify                    # 99 tests; integration tests use Testcontainers MySQL (Docker)
```

Local credentials (compose only): admin secret `local-admin-secret`; MySQL on `localhost:3306`, user `root`, password `seats`, database `seats`.

---

## API

JSON is snake_case. Money is integer paise. Every response carries `X-Request-Id`; every error body is `{"error": "...", "message": "...", "request_id": "..."}`.

### Get a token

Identity comes **only** from the bearer token. A `user_id` sent in a request body is ignored.

```sh
U=https://seat-reservation-production-b408.up.railway.app

# user token (any user_id: 1–64 chars of A-Z a-z 0-9 _ -)
curl -s -H 'Content-Type: application/json' -d '{"user_id":"alice"}' $U/auth/token

# admin token (needed for POST /shows)
curl -s -H 'Content-Type: application/json' \
  -d '{"user_id":"ops","admin_secret":"ee604549ef0223cda68ba9875ca0a38e"}' $U/auth/token
```

`POST /auth/token` is open on purpose: it stands in for an identity provider so a load test can mint thousands of users. Tokens are HS256 JWTs, valid for 1 hour.

### Endpoints

| Method and path | Auth | Success | Declines and errors |
|---|---|---|---|
| `POST /auth/token` | none | 200 `{token, user_id, role, expires_in}` | 400 bad user_id · 403 wrong admin_secret |
| `POST /shows` | admin | 201 show with every seat `available` | 400 invalid body · 401 · 403 not admin |
| `GET /shows/{id}` | none | 200 per-seat status + `counts {available, held, confirmed, total}` | 404 |
| `POST /shows/{id}/reserve` | user | **201** new reservation · **200** replay of the same idempotency key | **409** `seat-taken` / `per-user-limit` / `idempotency-key-reused` · 429 `overloaded` · 400 · 401 · 404 |
| `POST /reservations/{id}/cancel` | owner | 200 cancelled reservation (also on a repeat cancel) | 403 not the owner · 404 · 401 |

```sh
ADMIN=<admin token>; ALICE=<alice's token>

curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":4}' $U/shows

curl -s -H 'Content-Type: application/json' -H "Authorization: Bearer $ALICE" \
  -d '{"seats":["A1","A2"],"idempotency_key":"order-42"}' $U/shows/<show_id>/reserve
# → 201 {"reservation_id":"…","show_id":"…","user_id":"alice","seats":["A1","A2"],"amount_paise":50000,"status":"confirmed"}

curl -s -H "Authorization: Bearer $ALICE" -X POST $U/reservations/<reservation_id>/cancel
curl -s $U/shows/<show_id>
```

The idempotency key can also be sent as an `Idempotency-Key` header (if both are sent they must match).

---

## Decisions where the assignment was open

| Question | Decision |
|---|---|
| Hold or confirm? | Reserve **confirms immediately** (`status: "confirmed"`, as in the sample response). Release is the explicit owner-only `POST /reservations/{id}/cancel`. No timed holds, so `held` is always 0 (it is still reported). |
| Partial requests | **All-or-nothing.** If any requested seat is taken, nothing is reserved: 409 `seat-taken`. |
| Replay status | Same key and same request → **200** with the original reservation, so each hot seat still sees exactly one 201. Counted as `reservations_declined_total{reason="idempotent-replay"}`, as the assignment's metric list names it. |
| "Same request" | Same show and same set of seats, in any order. Same key with different seats or a different show → 409 `idempotency-key-reused`. |
| Declined attempt, then a retry with the same key | A decline reserves nothing and stores nothing, so the retry is a fresh attempt. |
| Retry after cancel | Returns the original (now `cancelled`) reservation; it does not book again. |
| Per-user limit | Optional `per_user_limit` on `POST /shows`, default **4**, per show, counting confirmed seats. Cancel gives the seats back. |
| Overload | When the service can't get a DB connection or row lock in time: **429 `overloaded`** with `Retry-After`, never 5xx. The transaction has rolled back, so a retry is safe. |
| Show state | `GET /shows/{id}` is public and shows no owners. |
| Who may cancel | Only the owner. An admin token is not an owner. |

---

## How correctness is enforced (short version)

Full reasoning in [DESIGN.md §4](DESIGN.md#4-concurrency-design-mysql--innodb) and [WRITEUP.md](WRITEUP.md).

- **No double-sell:** `UPDATE seats SET status='confirmed', reservation_id=? WHERE show_id=? AND seat_label IN (…) AND status='available'`. The check and the write are one statement on the row; if fewer rows change than were requested, the whole transaction rolls back and the answer is 409.
- **No deadlocks on multi-seat requests:** seats are locked one by one in sorted label order (`SELECT … FOR UPDATE`), so overlapping requests always lock in the same order. A deadlock retry (up to 5 attempts) covers the one InnoDB case that order can't prevent.
- **Idempotency:** `UNIQUE (user_id, idempotency_key)` on the reservation row, which is inserted first in the transaction.
- **Per-user limit:** one counter row per (show, user), updated with `… WHERE seat_count + n <= limit` in the same transaction.
- **Invariant:** one row per seat with one status column, so `available + held + confirmed == total` holds by construction; `GET /shows/{id}` reads all seats in one statement.
- **Fast path:** before opening a transaction, one plain read rejects requests that can't succeed (seat already taken, key already used, unknown seat). It never confirms anything (read-then-reject), which is what lets 500 losers of a hot seat get a quick 409 instead of queueing.

---

## Observability

**Metrics** at `/actuator/prometheus`:

| Metric | Meaning |
|---|---|
| `reservations_confirmed_total` | reservations created (201) |
| `reservations_declined_total{reason}` | `seat-taken`, `per-user-limit`, `idempotency-key-reused`, `idempotent-replay`, `overloaded` |
| `reservations_cancelled_total` | cancels that released seats |
| `seats_available{show_id}` | read from the database at scrape time, so it always equals the API |
| `http_server_requests_seconds_*` | per endpoint and status, including any 5xx |
| `hikaricp_connections_*` | connection pool usage and waits |

**Logs** are JSON, one object per line, with `request_id` (from or echoed in `X-Request-Id`) and `user_id` on every line of a request, plus one line per reserve or cancel outcome with `outcome`, `reason`, `show_id`, `reservation_id`, `seat_count` and `duration_ms`. Tokens and secrets are never logged.

Railway does not offer public log access, so here is a screen recording of the live logs streaming during a burst against the live URL: **[live logs under load (Google Drive)](https://drive.google.com/file/d/1dmNx2Fx_7Pgz0AM4Sbrp8966EhI9Fp1F/view?usp=sharing)**.

**Health:** liveness never touches the database; readiness includes a database check and returns 503 when the database is unreachable. Railway only routes traffic to a deployment once readiness passes.

---

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | none | JDBC URL, e.g. `jdbc:mysql://host:port/db?sslMode=REQUIRED` |
| `DB_USERNAME`, `DB_PASSWORD` | none | database credentials |
| `JWT_SECRET` | none | HS256 signing key, at least 32 bytes; the app refuses to start without it |
| `ADMIN_SECRET` | none | needed to mint admin tokens; the app refuses to start without it |
| `DB_POOL_SIZE` / `DB_POOL_MIN_IDLE` | 10 / 10 | connection pool max / idle floor (live: 50 / 30) |
| `DB_CONNECTION_TIMEOUT_MS` | 10000 | wait for a pooled connection before answering 429 |
| `DB_CONNECT_TIMEOUT_MS` | 30000 | open a new TCP + TLS connection to MySQL |
| `DB_LOCK_WAIT_TIMEOUT_SECONDS` | 5 | InnoDB row-lock wait before answering 429 |
| `TOKEN_TTL_SECONDS` | 3600 | token lifetime |
| `PORT` | 8080 | HTTP port (set by the platform) |

Deploy files: [Dockerfile](Dockerfile) (multi-stage, JRE, non-root; runs on arm64 and amd64), [docker-compose.yml](docker-compose.yml), [railway.json](railway.json). [keep-alive.yml](.github/workflows/keep-alive.yml) pings readiness every 10 minutes so the free Aiven database is not powered off as idle.

**Free-tier caveats:** the Railway trial lasts 30 days from sign-up; if the service is down when you test, that is the likely reason, and `docker compose up --build` reproduces it locally.
