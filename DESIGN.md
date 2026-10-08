# Design: Seat Reservation at Scale

Status: **approved**. Database switched from PostgreSQL to MySQL 8.4 during milestone 3. The concurrency mechanisms in section 4 are adapted to InnoDB.

Source of truth for requirements: the assignment text (`PAYTM_SEAT_RESERVATION_REQUIREMENTS.md`). It allows any datastore. This document only fills gaps the assignment leaves open; every such choice is marked **Decision** and will be repeated in the README.

---

## 1. Architecture

```
client ──HTTP──> Spring Boot app (1 instance) ──JDBC──> MySQL 8.4 / InnoDB (1 instance)
                   │
                   ├─ /actuator/health/{liveness,readiness}
                   └─ /actuator/prometheus
```

- Java 21, Spring Boot 3, Maven (with `mvnw` wrapper so a clean clone needs no local Maven).
- Database access: Spring `JdbcTemplate` + `TransactionTemplate`. No JPA.
- Libraries: Flyway (migrations), jjwt (JWT), Micrometer Prometheus registry. JSON logs use Spring Boot's built-in structured logging (`logging.structured.format.console: logstash`), so no extra logging library. Testcontainers for integration tests.
- No Spring Security. A controller method that needs a caller declares an `AuthenticatedUser` parameter. `AuthenticatedUserArgumentResolver` fills it from the `Authorization: Bearer` header via `TokenService.verify`, or throws 401. Endpoints without that parameter are public. Unlike a servlet filter, a failure here goes through the same `GlobalExceptionHandler` as every other error.
- Virtual threads on (`spring.threads.virtual.enabled=true`): a request waiting for a DB connection parks cheaply instead of holding a platform thread. The DB pool becomes the only throttle. Connector/J 9.x uses `ReentrantLock` rather than `synchronized`, so virtual threads don't get pinned.
- **Isolation: READ COMMITTED** (set on the Hikari pool). MySQL's default REPEATABLE READ takes gap locks, which cause extra lock waits and deadlocks under a burst. READ COMMITTED locks only the rows actually read with `FOR UPDATE` or written.
- `rewriteBatchedStatements=true` so the seat batch insert is one multi-row `INSERT`.

### Packages (one top-level class per file, no inner classes)

```
com.paytm.seatreservation
  controller   AuthController, ShowController, ReservationController
  service      ShowService, ReservationService, TokenService
  dao          ShowDao, SeatDao, ReservationDao, UserSeatCountDao
  dto          request/response records
  model        records the DAOs return and the services work with
  exception    domain exceptions + GlobalExceptionHandler
  security     AuthenticatedUserArgumentResolver, AuthenticatedUser, Role
  observability RequestIdFilter, ReservationMetrics
  config       WebConfig (registers the resolver)
```

Controller = HTTP + validation. Service = all business rules and transaction boundaries. DAO = SQL only.

---

## 2. Schema

All ids, labels, statuses and keys use **`ascii_bin` / `utf8mb4_bin` (binary, case-sensitive) collation**. MySQL's default collation is case-insensitive, so `a1` and `A1` would collide on the primary key and sort differently from Java.

```sql
CREATE TABLE shows (
  id             CHAR(36)     ascii_bin PRIMARY KEY,   -- UUID as text: readable in Workbench
  name           VARCHAR(100) NOT NULL,
  price_paise    BIGINT       NOT NULL CHECK (price_paise >= 0),
  per_user_limit INT          NOT NULL CHECK (per_user_limit > 0),
  total_seats    INT          NOT NULL CHECK (total_seats > 0),
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE seats (
  show_id        CHAR(36)    ascii_bin NOT NULL REFERENCES shows(id),
  seat_label     VARCHAR(16) ascii_bin NOT NULL,
  position       INT         NOT NULL,  -- creation order, so listings read A1, A2, ..., A10
  status         VARCHAR(10) ascii_bin NOT NULL CHECK (status IN ('available','held','confirmed')),
  reservation_id CHAR(36)    ascii_bin NULL,
  PRIMARY KEY (show_id, seat_label),
  CHECK ((status = 'available') = (reservation_id IS NULL)),
  INDEX seats_reservation_idx (reservation_id)           -- added with reservations (milestone 5)
);

CREATE TABLE reservations (
  id              CHAR(36)     ascii_bin PRIMARY KEY,
  show_id         CHAR(36)     ascii_bin NOT NULL REFERENCES shows(id),
  user_id         VARCHAR(64)  ascii_bin NOT NULL,
  seats           JSON         NOT NULL,                 -- e.g. ["A12","A13"]; MySQL has no array type
  amount_paise    BIGINT       NOT NULL,
  status          VARCHAR(10)  ascii_bin NOT NULL CHECK (status IN ('confirmed','cancelled')),
  idempotency_key VARCHAR(128) utf8mb4_bin NOT NULL,
  request_hash    CHAR(64)     ascii_bin NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  cancelled_at    DATETIME(3)  NULL,
  UNIQUE KEY reservations_user_key (user_id, idempotency_key)
);

CREATE TABLE user_seat_counts (
  show_id    CHAR(36)    ascii_bin NOT NULL REFERENCES shows(id),
  user_id    VARCHAR(64) ascii_bin NOT NULL,
  seat_count INT         NOT NULL CHECK (seat_count >= 0),
  PRIMARY KEY (show_id, user_id)
);
```

All tables are InnoDB. CHECK constraints are enforced since MySQL 8.0.16.

Why this shape:

- **One row per seat, one status column.** A seat cannot be in two states, so `available + held + confirmed == total_seats` holds by construction. `GET /shows/{id}` reads all seats in one statement and derives the counts from that same result, so the list and the counts come from one snapshot and agree even mid-burst.
- **The reservation row is the idempotency record.** Declines are not stored (agreed: a retry after a decline is a fresh attempt), so a key only needs a row when a reservation exists. `UNIQUE (user_id, idempotency_key)` makes "reserve twice with one key" impossible. No separate idempotency table.
- **`user_seat_counts`** gives the per-user limit one row to lock and update conditionally, instead of counting seats under a race.
- The CHECK on `seats` stops a bug from ever leaving a confirmed seat with no owner, or an available seat with one.
- `held` is allowed by the CHECK but never written (reserve confirms immediately).

---

## 3. API contract

JSON uses snake_case. Every error body:

```json
{ "error": "seat-taken", "message": "Seat A12 is not available", "request_id": "…" }
```

`error` values for 409 match the metric `reason` labels.

### `POST /auth/token` (no auth)

Test-harness endpoint so a burst can mint tokens for thousands of users.

```json
{ "user_id": "u-123", "admin_secret": "optional" }
→ 200 { "token": "<jwt>", "user_id": "u-123", "role": "user", "expires_in": 3600 }
```

- JWT is HS256, signed with `JWT_SECRET` (env, at least 32 bytes). `sub` = user id, `role` = `user` or `admin`. Lifetime `TOKEN_TTL_SECONDS` (default 3600).
- `role=admin` only if `admin_secret` equals `ADMIN_SECRET` (env), compared in constant time. Wrong secret → 403.
- The app refuses to start if `JWT_SECRET` or `ADMIN_SECRET` is missing, or if `JWT_SECRET` is too short.
- Expired, tampered or wrongly signed token, or a non-`Bearer` scheme → 401 with `WWW-Authenticate: Bearer`. Auth is checked before the request body is parsed.
- **Decision**: anyone can mint a user token. This is a stand-in for a real identity provider and is documented as such. It does not weaken requirement 6: identity is still taken only from the token, never from a request body.

| Case | Code |
|---|---|
| ok | 200 |
| missing/invalid `user_id` (1–64 chars, `[A-Za-z0-9_-]`) | 400 |
| wrong `admin_secret` | 403 |

### `POST /shows` (admin token)

```json
{ "name": "friday-night", "seats": ["A1","A2"], "price_paise": 25000, "per_user_limit": 4 }
→ 201 { "id": "…", "name": "friday-night", "price_paise": 25000, "per_user_limit": 4,
        "total_seats": 2, "counts": {...}, "seats": [{"label":"A1","status":"available"}, ...] }
```

- `per_user_limit` optional, default 4.
- Seats inserted with one JDBC batch.
- Jackson `ACCEPT_FLOAT_AS_INT` is turned off, so `"price_paise": 250.5` is a 400, not silently truncated.

| Case | Code |
|---|---|
| created | 201 |
| no/invalid token | 401 |
| non-admin token | 403 |
| blank name, empty seats, duplicate labels, bad label (`[A-Za-z0-9-]{1,16}`), > 10,000 seats, negative/float price, limit < 1, bad JSON | 400 |

### `GET /shows/{id}` (no auth)

```json
→ 200 { "id": "…", "name": "…", "price_paise": 25000, "per_user_limit": 4, "total_seats": 2,
        "counts": { "available": 1, "held": 0, "confirmed": 1, "total": 2 },
        "seats": [{"label":"A1","status":"confirmed"}, {"label":"A2","status":"available"}] }
```

**Decision**: public read, no owner info exposed. Lets graders and the burst script check state without a token.

| Case | Code |
|---|---|
| ok | 200 |
| unknown id / not a UUID | 404 |

### `POST /shows/{id}/reserve` (user token)

```json
{ "seats": ["A12"], "idempotency_key": "k-1" }
→ 201 { "reservation_id": "…", "show_id": "…", "user_id": "u-123", "seats": ["A12"],
        "amount_paise": 25000, "status": "confirmed" }
```

- Key from body `idempotency_key` or header `Idempotency-Key`. Both present and different → 400.
- Any `user_id` in the body is ignored. Identity = token `sub`.
- `amount_paise = price_paise × seat count`, `long` with `Math.multiplyExact`.
- Seats returned sorted.

| Case | Code | `error` |
|---|---|---|
| reserved | 201 | |
| same key, same request (replay) | 200, original reservation (status may be `cancelled`) | |
| any requested seat not available (all-or-nothing) | 409 | `seat-taken` |
| would exceed `per_user_limit` | 409 | `per-user-limit` |
| same key, different show or seats | 409 | `idempotency-key-reused` |
| could not get a DB connection or row lock in time, or deadlock retries exhausted | 429 | `overloaded` |
| no/invalid token | 401 | |
| unknown show | 404 | |
| empty seats, duplicate seats, unknown seat label, missing key, key > 128 chars, bad JSON | 400 | |

"Same request" means same `show_id` and same set of seats. Order does not matter: `["A13","A12"]` replays `["A12","A13"]`. The hash is SHA-256 of `show_id` + sorted seats.

### `POST /reservations/{id}/cancel` (user token)

```json
→ 200 { ...reservation..., "status": "cancelled" }
```

| Case | Code |
|---|---|
| cancelled | 200 |
| already cancelled by owner (retry) | 200, same body |
| reservation belongs to another user | 403 |
| unknown id | 404 |
| no/invalid token | 401 |

### Ops endpoints (no auth)

| Path | Meaning |
|---|---|
| `GET /actuator/health/liveness` | process up; does not touch DB |
| `GET /actuator/health/readiness` | includes DB check; 503 when DB down (fails closed) |
| `GET /actuator/prometheus` | metrics |

Every response carries `X-Request-Id`.

---

## 4. Concurrency design (MySQL / InnoDB)

### Reserve: one transaction, READ COMMITTED

```
0. Controller validation: seats non-empty, distinct, well-formed labels; idempotency key present (else 400)

0b. Fast path: one plain read, no transaction, no locks
   SELECT show columns,
          (COUNT of requested seats, COUNT of those not 'available'),
          this user's reservation for this idempotency key (LEFT JOIN reservations)
   FROM shows ... WHERE id = ?
   → no show: 404.
   → key already used: same request → 200 with the original reservation; different request → 409 key-reused. Stop.
   → a requested seat doesn't exist: 400 (seats are never deleted, so this can't change). Stop.
   → more seats than the limit: 409 per-user-limit. Stop.
   → any requested seat taken: 409 seat-taken. Stop.

BEGIN
1. Claim the idempotency key
   INSERT INTO reservations (...) VALUES (...)
   → duplicate key (MySQL error 1062): key already used.
     ROLLBACK, SELECT the existing row; same hash → 200 replay, else 409. Stop.

2. Per-user limit (two statements)
   a. INSERT INTO user_seat_counts (show_id, user_id, seat_count) VALUES (?, ?, 0)
      ON DUPLICATE KEY UPDATE seat_count = seat_count        -- make sure the row exists; no-op if it does
   b. UPDATE user_seat_counts SET seat_count = seat_count + :n
      WHERE show_id = ? AND user_id = ? AND seat_count + :n <= :limit
      → 0 rows: over limit → ROLLBACK, 409 per-user-limit.

3. Multi-seat requests only: lock the seats in a fixed order, one statement per seat, labels sorted in Java
   SELECT status FROM seats WHERE show_id = ? AND seat_label = ? FOR UPDATE
   (A single seat has no lock order to get wrong and goes straight to step 4. Under READ COMMITTED, an UPDATE whose
   WHERE no longer matches neither waits for nor keeps the row lock, so once a hot seat is sold, each loser fails in
   one round trip instead of queueing on the lock.)

4. Claim the seats (the atomic decision)
   UPDATE seats SET status = 'confirmed', reservation_id = ?
   WHERE show_id = ? AND seat_label IN (?, ?, ...) AND status = 'available'
   → rows updated < requested: some seat taken → ROLLBACK, 409 seat-taken.
COMMIT
```

Why it is race-free:

- **No double-sell.** Step 4 changes a seat only if its status is still `available`, and that check and write happen in one statement on a row this transaction has locked. For a hot seat, 500 transactions queue on the row lock taken in step 3. The first commits `confirmed`. Each of the rest then gets the lock; in InnoDB a locking read (`FOR UPDATE`) and an `UPDATE` always see the latest committed row, so they see `confirmed`, update 0 rows, and get 409. Exactly one 201.
- **Per-user limit.** Step 2b is a guarded update on the user's counter row, so it takes that row's lock. Ten parallel requests from one user run 2b one at a time, each seeing the committed total of the ones before. A rollback (say, seat taken) also undoes the increment, so the count never drifts. Requests bigger than the limit on their own are rejected in step 0.
  - Why two statements and not one upsert: MySQL's `ON DUPLICATE KEY UPDATE` has no `WHERE`. A guarded single upsert needs an `IF()` expression and a driver flag (`useAffectedRows`) to tell "updated" from "unchanged". Two plain statements are easier to read and to explain.
- **Idempotency.** The unique key makes a second row for the same key impossible. Two concurrent requests with one key: the second `INSERT` waits on the first's uncommitted index entry. If the first commits, the second gets error 1062, reads the committed row, and replays it (200). If the first rolls back (seat taken), the second's insert succeeds and it proceeds as a fresh attempt. Either way at most one reservation exists per key.
  - In MySQL a duplicate-key error does not abort the transaction (unlike Postgres), but we roll back anyway: nothing else has happened yet.
- **Fast path (0b) is read-then-reject, never read-then-write.** It can only turn a request away; the guarded `UPDATE` in step 4 is still the only place a seat is confirmed. A stale read can only decline a seat released a moment earlier, never sell one twice. Why it exists: without it, the hundreds of losers of a hot seat each hold a DB connection while queueing on the seat's row lock, and time out into 429s. Measured at 1 CPU with a 20k burst: hot-seat storm went from 267 to 475 req/s and 429s from 1,396 to 996; with the 10s pool wait as well, 1 × 429 and every check passing.
  - It is one statement on purpose. Under READ COMMITTED a single SELECT reads one consistent snapshot, and a seat and its reservation commit in the same transaction, so if the snapshot shows our own earlier attempt's seat as taken, it also shows that reservation, and the retry gets 200. Two separate reads raced: an earlier version read the key, then the seats, and a retry saw "no key" then "seat taken" and got 409; an existing concurrency test caught it.
- **All-or-nothing.** Steps 1–4 are one transaction. Any failure rolls back everything: no reservation, no counter change, no seat changed.

### Deadlock avoidance

Every transaction takes locks in one global order:

1. the reservation's unique-key entry
2. the `user_seat_counts` row
3. seat rows, **sorted by `seat_label`**, locked one at a time in step 3

Two requests for `["A1","A2"]` and `["A2","A1"]` both lock A1 first, so neither can hold A2 while waiting for A1. No cycle.

Why one `SELECT ... FOR UPDATE` per seat instead of one `IN (...)` query: InnoDB locks rows in the order it scans the index, and the manual does not promise that order. Locking each seat explicitly in sorted order makes the order guaranteed and visible in the code. A request has at most `per_user_limit` seats (default 4), so this is at most a handful of primary-key lookups.

**Deadlock retry (safety net).** InnoDB can still deadlock in one known case that lock order does not prevent. Three requests insert the same new unique key at once (same idempotency key, or a user's first-ever counter row) and the first rolls back. The two waiters both hold shared locks and both want an exclusive one. InnoDB detects this immediately and aborts one with error 1213. Because the whole transaction rolled back, it is safe to re-run. `TransactionRunner` re-runs the transaction up to 5 attempts with a short random backoff (10–40 ms × attempt). If it still fails, it returns 429 `overloaded` and logs at WARN. Measured: 50 concurrent same-key requests for a taken seat cause about 50 deadlock retries. With 3 attempts, 0–5 of the 50 ended as 429; with 5 attempts, none did across 5 runs.

### Cancel: one transaction, same lock order

```
BEGIN
1. SELECT user_id, show_id, seats, status FROM reservations WHERE id = ? FOR UPDATE
   → missing → 404; user_id ≠ token user → 403; already cancelled → 200 (same body).
2. UPDATE reservations SET status = 'cancelled', cancelled_at = NOW(3) WHERE id = ?
3. UPDATE user_seat_counts SET seat_count = seat_count - :n WHERE show_id = ? AND user_id = ?
4. Lock this reservation's seats, sorted by label (same as reserve step 3)
5. UPDATE seats SET status = 'available', reservation_id = NULL WHERE reservation_id = ?
COMMIT
```

MySQL has no `UPDATE ... RETURNING`, so step 1 reads the reservation with `FOR UPDATE`. The row is locked from that point, so the owner and status checks cannot go stale before step 2.

- **Owner only.** The reservation row is locked before the owner check, and nothing can change it until commit.
- **Never resurrects someone else's seat.** Step 5 frees only seats whose `reservation_id` is this reservation. A seat re-booked by someone else carries their reservation id and is untouched.
- **Cancel twice in parallel.** The second waits on the reservation row lock, then finds `status = 'cancelled'` and returns 200 without decrementing again.
- Same lock order as reserve (reservation → counter → sorted seats), so cancel and reserve cannot deadlock.

---

## 5. Zero 5xx under a burst

A 5xx under load usually comes from something running out, not from logic. The plan for each:

| Resource | Default failure | Plan |
|---|---|---|
| Request threads | Tomcat pool exhausted → connections queue or time out | Virtual threads: no thread cap. Waiting requests are cheap |
| DB connections | Hikari timeout throws → 500 | Small pool (`DB_POOL_SIZE`, default 10, under the DB's connection cap). Requests queue for a connection. Timeout (`DB_CONNECTION_TIMEOUT_MS`, default 10s) maps to **429 `overloaded`**. Opening a *new* connection (TCP + TLS) has its own limit, `DB_CONNECT_TIMEOUT_MS` (30s): on a small CPU share the TLS handshake alone took over 3s and crashed startup |
| Row-lock waits | InnoDB default `innodb_lock_wait_timeout` is 50s | Transactions are a few short statements, so locks are held for milliseconds. Session `innodb_lock_wait_timeout = 5` (set per connection by Hikari) as a backstop; error 1205 → 429 |
| Deadlock | Error 1213 → 500 | Retry the transaction up to 5 attempts, then 429 (section 4) |
| Expected DB errors | Duplicate key / check violation → 500 | Duplicate key on the idempotency key is caught and turned into replay/409. Everything else uses guarded updates checked by row count, not exceptions |

In code, `GlobalExceptionHandler` maps `CannotCreateTransactionException` and `CannotGetJdbcConnectionException` (no connection in time) and `PessimisticLockingFailureException` (lock wait timeout or deadlock) to 429 with `Retry-After: 1`. The transaction has already rolled back, so a retry is safe.

Genuine bugs still return 500. We hide nothing; we just make sure load alone never produces one.

Counters are incremented **after** commit, so a rolled-back attempt is never counted as confirmed.

---

## 6. Observability

### Metrics (`/actuator/prometheus`)

| Metric | Type | Labels |
|---|---|---|
| `reservations_confirmed_total` | counter | |
| `reservations_declined_total` | counter | `reason` = `seat-taken`, `per-user-limit`, `idempotent-replay`, `idempotency-key-reused`, `overloaded` |
| `reservations_cancelled_total` | counter | |
| `seats_available` | gauge | `show_id` |
| `http_server_requests_seconds` | built-in timer | `uri`, `status` (shows any 5xx) |
| `hikaricp_connections_*` | built-in | pool usage / pending |

- `idempotent-replay` is counted as a decline, as the assignment's metric list names it. Its HTTP code is 200.
- **`seats_available` reads the DB on each scrape** (`count(*) WHERE status='available'`, by show). So it always equals what `GET /shows/{id}` reports. A gauge is registered per show on creation and on startup for existing shows.
- Reconciliation: `reservations_confirmed_total` counts reservations, not seats. For single-seat bursts, `total_seats − seats_available == confirmed − cancelled`. The burst script checks this.

### Logs

- JSON to stdout, one object per line, via Spring Boot's built-in structured logging in Logstash format.
- `RequestIdFilter`: takes incoming `X-Request-Id` or generates a UUID. Puts it in MDC as `request_id` and echoes it in the response header. `user_id` is added to MDC after auth.
- One INFO line per reserve or cancel outcome: `event`, `outcome`, `reason`, `show_id`, `reservation_id`, `seat_count`, `duration_ms`. The line is written by the same `ReservationMetrics` call that increments the counter, so logs and metrics always agree.
- Error bodies carry `request_id`, matching the `X-Request-Id` header and the request's log lines.
- Never logged: tokens, secrets.

### Health

- Spring Actuator probes. The readiness group is `readinessState` + `db`. DB down → readiness 503; liveness stays 200, so the platform waits for the DB instead of restart-looping.
- Flyway runs before the app reports ready.

---

## 7. Burst script

`./burst.sh <BASE_URL>`. It's a thin wrapper around a single-file Java program (`burst/Burst.java`, run with `java Burst.java`, no build step), using `java.net.http.HttpClient` and virtual threads. If no local JDK 21+ is found, it runs the same file in the `eclipse-temurin:21` Docker image.

Phases:

1. **Setup:** admin token; create a fresh show (default 1,000 seats, limit 4); mint user tokens (not timed).
2. **Hot-seat storm:** 500 users at once on each of 5 hot seats.
3. **Stampede:** ~20,000 concurrent single- and multi-seat reserves from many users, skewed toward a hot block of seats.
4. **Retries:** resend some requests with the same key (expect 200 replay), and some with the same key but different seats (expect 409).
5. **Per-user limit:** one user fires 10 parallel reserves on a limit-4 show.
6. **Report:**
   - outcome counts by status code and `error`, with 5xx called out
   - per hot seat: number of 201s (must be 1)
   - no seat confirmed to two users (from response bodies)
   - final `GET /shows/{id}` counts and the invariant check
   - metrics scrape compared with API state

   PASS or FAIL for each check.

Concurrency, user count and seat count are flags.

---

## 8. Container and deploy

- **Dockerfile**, multi-stage: `eclipse-temurin:21-jdk` builds with `mvnw`; `eclipse-temurin:21-jre` runs it. Both images are multi-arch, so it runs on the Apple Silicon Mac and on Linux amd64 hosts.
- JVM flag `-XX:MaxRAMPercentage=75`, so it fits a small free-tier container.
- **docker-compose.yml**: `mysql:8.4` with a healthcheck, plus the app with `depends_on: condition: service_healthy`. `docker compose up --build` is the one command.
- All settings come from env vars (`DB_URL` as a JDBC URL, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_SECRET`, `DB_POOL_SIZE`, `DB_CONNECTION_TIMEOUT_MS`, `PORT`), with local values in compose only.
- **Hosting (free, no card): app on Railway (trial: up to 2 vCPU / 1 GB), MySQL 8 on Aiven (free plan: 1 GB, 76 connections, TLS required).** `railway.json` builds the same Dockerfile and gates traffic on the readiness endpoint. The JDBC URL carries `sslMode=REQUIRED`.
  - Rejected: Render free (0.1 CPU). Measured with the image capped at 0.1 CPU: ~11–20 req/s, ~2.5 min startup, 96% of a burst answered 429, hot-seat checks failed. At 1 CPU everything passes.
  - Rejected: MySQL on Railway too. It would use up the $5 trial credit in ~2.5 weeks; Aiven is free without a time limit.
  - Aiven powers off an idle free database: `.github/workflows/keep-alive.yml` calls readiness (which queries the DB) every 10 minutes.
  - Measured live (Railway Singapore ↔ Aiven DigitalOcean Bengaluru): each request held a pooled connection ~208 ms on average because every statement is a network round trip, so 30 connections gave ~144 req/s and a 20k burst produced 2,657 × 429 (zero 5xx, no double-sell). Fixes: show lookup and seat pre-check merged into one statement (losers now make one round trip), pool 50 with a 10-connection idle floor so a redeploy's overlap stays under Aiven's 76.
  - Risk: the Railway trial ends 30 days after sign-up. Avoid MySQL-compatible engines like TiDB, whose locking differs from InnoDB and would invalidate section 4.

---

## 9. Testing

- **Unit:** request hashing, validation, amount calculation.
- **Integration** (Testcontainers `mysql:8.4`): every status code in section 3.
- **Concurrency** (Testcontainers, many threads released at once by a latch):
  - 200 threads on one seat → exactly one 201, rest 409
  - multi-seat overlapping requests in opposite orders → no deadlock, no partial booking
  - one user, 10 parallel reserves, limit 4 → at most 4 seats
  - same key from 50 threads → one reservation, the rest 200 replay (exercises the deadlock retry)
  - reserve vs cancel races → invariant holds, no resurrected seat
  - after each test: `available + held + confirmed == total`, and `user_seat_counts` equals the actual confirmed seats per user

---

## 10. Resolved items

1. **Docker:** installed.
2. **JDK:** Java 21 locally and in the image.
3. **Build tool:** Maven with wrapper.
4. **Burst script:** single-file Java.
5. **Database:** MySQL 8.4, chosen for the candidate's familiarity. GUI: MySQL Workbench.
6. **Deploy platform:** Railway (app) + Aiven (MySQL), free, no card; see section 8.
