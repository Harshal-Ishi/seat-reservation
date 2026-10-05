# Design: Seat Reservation at Scale

Status: **approved**.

Source of truth for requirements: the assignment text (`PAYTM_SEAT_RESERVATION_REQUIREMENTS.md`). This document only fills gaps the assignment leaves open; every such choice is marked **Decision** and will be repeated in the README.

---

## 1. Architecture

```
client ──HTTP──> Spring Boot app (1 instance) ──JDBC──> PostgreSQL (1 instance)
                   │
                   ├─ /actuator/health/{liveness,readiness}
                   └─ /actuator/prometheus
```

- Java 21, Spring Boot 3, Maven (with `mvnw` wrapper so a clean clone needs no local Maven).
- Database access: Spring `JdbcTemplate` + `TransactionTemplate`. No JPA.
- Libraries: Flyway (migrations), jjwt (JWT), Micrometer Prometheus registry, logstash-logback-encoder (JSON logs). Testcontainers for integration tests.
- No Spring Security: auth is one servlet filter that verifies the JWT. Less machinery to explain.
- Virtual threads on (`spring.threads.virtual.enabled=true`): a request waiting for a DB connection parks cheaply instead of holding a platform thread. The DB pool becomes the only throttle.

### Packages (one top-level class per file, no inner classes)

```
com.paytm.seatreservation
  controller   AuthController, ShowController, ReservationController
  service      ShowService, ReservationService, TokenService
  dao          ShowDao, SeatDao, ReservationDao, UserSeatCountDao
  dto          request/response records
  exception    domain exceptions + GlobalExceptionHandler
  security     JwtAuthFilter, AuthenticatedUser
  observability RequestIdFilter, ReservationMetrics
  config       app properties, Jackson config
```

Controller = HTTP + validation. Service = all business rules and transaction boundaries. DAO = SQL only.

---

## 2. Schema

```sql
CREATE TABLE shows (
  id             UUID PRIMARY KEY,
  name           TEXT        NOT NULL,
  price_paise    BIGINT      NOT NULL CHECK (price_paise >= 0),
  per_user_limit INT         NOT NULL CHECK (per_user_limit > 0),
  total_seats    INT         NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seats (
  show_id        UUID NOT NULL REFERENCES shows(id),
  seat_label     TEXT NOT NULL,
  status         TEXT NOT NULL CHECK (status IN ('available','held','confirmed')),
  reservation_id UUID NULL,
  PRIMARY KEY (show_id, seat_label),
  CHECK ((status = 'available') = (reservation_id IS NULL))
);
CREATE INDEX seats_reservation_idx ON seats (reservation_id);

CREATE TABLE reservations (
  id              UUID PRIMARY KEY,
  show_id         UUID   NOT NULL REFERENCES shows(id),
  user_id         TEXT   NOT NULL,
  seats           TEXT[] NOT NULL,
  amount_paise    BIGINT NOT NULL,
  status          TEXT   NOT NULL CHECK (status IN ('confirmed','cancelled')),
  idempotency_key TEXT   NOT NULL,
  request_hash    TEXT   NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  cancelled_at    TIMESTAMPTZ NULL,
  UNIQUE (user_id, idempotency_key)
);

CREATE TABLE user_seat_counts (
  show_id    UUID NOT NULL REFERENCES shows(id),
  user_id    TEXT NOT NULL,
  seat_count INT  NOT NULL CHECK (seat_count >= 0),
  PRIMARY KEY (show_id, user_id)
);
```

Why this shape:

- **One row per seat, one status column.** A seat cannot be in two states, so `available + held + confirmed == total_seats` holds by construction. `GET /shows/{id}` counts with one `GROUP BY` statement, which reads one consistent snapshot, so the invariant holds even mid-burst.
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

- JWT is HS256, signed with `JWT_SECRET` (env). `sub` = user id, `role` = `user` or `admin`.
- `role=admin` only if `admin_secret` equals `ADMIN_SECRET` (env). Wrong secret → 403.
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
| could not get a DB connection or row lock in time | 429 | `overloaded` |
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

## 4. Concurrency design

### Reserve: one transaction, READ COMMITTED

```
0. SELECT show (price, limit)                       → 404 if missing
   validate: seats non-empty, distinct, count <= limit (else 409 per-user-limit)

BEGIN
1. Claim the idempotency key
   INSERT INTO reservations (...) VALUES (...)
   ON CONFLICT (user_id, idempotency_key) DO NOTHING
   → 0 rows: key already used. SELECT it; same hash → 200 replay, else 409. Stop.

2. Per-user limit
   INSERT INTO user_seat_counts (show_id, user_id, seat_count) VALUES (?, ?, :n)
   ON CONFLICT (show_id, user_id) DO UPDATE
     SET seat_count = user_seat_counts.seat_count + EXCLUDED.seat_count
     WHERE user_seat_counts.seat_count + EXCLUDED.seat_count <= :limit
   → 0 rows: over limit → ROLLBACK, 409 per-user-limit.

3. Lock the seats in a fixed order
   SELECT seat_label, status FROM seats
   WHERE show_id = ? AND seat_label = ANY(?)
   ORDER BY seat_label
   FOR UPDATE
   → fewer rows than requested: unknown label → ROLLBACK, 400.

4. Claim the seats (the atomic decision)
   UPDATE seats SET status = 'confirmed', reservation_id = ?
   WHERE show_id = ? AND seat_label = ANY(?) AND status = 'available'
   → rows updated < requested: some seat taken → ROLLBACK, 409 seat-taken.
COMMIT
```

Why it is race-free:

- **No double-sell.** Step 4 changes a seat only if its status is still `available`, and that check and write happen in one statement on a row this transaction has locked. For a hot seat, 500 transactions queue on the row lock. The first commits `confirmed`; each of the rest then sees `confirmed`, updates 0 rows, and gets 409. Exactly one 201.
- **Per-user limit.** The conditional upsert in step 2 locks the user's counter row. Ten parallel requests from one user run step 2 one at a time, each seeing the committed total of the ones before. A rollback (say, seat taken) also undoes the increment, so the count never drifts. Requests bigger than the limit on their own are rejected in step 0, because the first insert in step 2 has no `WHERE` to catch them.
- **Idempotency.** The unique index makes a second row for the same key impossible. Two concurrent requests with one key: the second `INSERT` waits on the first's uncommitted index entry. If the first commits, the second hits the conflict, reads the committed row, and replays it (200). If the first rolls back (seat taken), the second proceeds as a fresh attempt. Either way at most one reservation exists per key.
- **All-or-nothing.** Steps 1–4 are one transaction. Any failure rolls back everything: no reservation, no counter change, no seat changed.

### Deadlock avoidance

Every transaction takes locks in one global order:

1. reservation row / key index entry
2. `user_seat_counts` row
3. seat rows, **sorted by `seat_label`** (the `ORDER BY ... FOR UPDATE` in step 3)

Two requests for `["A1","A2"]` and `["A2","A1"]` both lock A1 first, so neither can hold A2 while waiting for A1. No cycle, no deadlock.

Step 3 is not a read-then-write. It takes the locks in order; the decision is still the guarded `UPDATE` in step 4. Without step 3, `UPDATE ... ANY(?)` would lock rows in whatever order Postgres scans them, which is not guaranteed.

Safety net: if Postgres still reports a deadlock (`40P01`), map it to 409 and log at ERROR. Should never happen; if it does we want to see it.

### Cancel: one transaction, same lock order

```
BEGIN
1. UPDATE reservations SET status='cancelled', cancelled_at=now()
   WHERE id = ? AND user_id = :tokenUser AND status = 'confirmed'
   RETURNING show_id, seats
   → 0 rows: SELECT reservation: missing → 404, other owner → 403,
     already cancelled → 200 (same body).
2. UPDATE user_seat_counts SET seat_count = seat_count - :n
   WHERE show_id = ? AND user_id = ?
3. SELECT ... FROM seats WHERE reservation_id = ? ORDER BY seat_label FOR UPDATE
4. UPDATE seats SET status='available', reservation_id=NULL
   WHERE reservation_id = ?
COMMIT
```

- **Owner only.** The `user_id = :tokenUser` guard is in the `WHERE`, so it is atomic, not a separate check.
- **Never resurrects someone else's seat.** Step 4 frees only seats whose `reservation_id` is this reservation. A seat re-booked by someone else carries their reservation id and is untouched.
- **Cancel twice in parallel.** The second waits on the reservation row lock, then finds `status='cancelled'`, updates 0 rows, and returns 200 without decrementing again.
- Same lock order as reserve (reservation → counter → sorted seats), so cancel and reserve cannot deadlock.

---

## 5. Zero 5xx under a burst

A 5xx under load usually comes from something running out, not from logic. The plan for each:

| Resource | Default failure | Plan |
|---|---|---|
| Request threads | Tomcat pool exhausted → connections queue or time out | Virtual threads: no thread cap. Waiting requests are cheap |
| DB connections | Hikari timeout throws → 500 | Small pool (`DB_POOL_SIZE`, default 10, under the DB's connection cap). Requests queue for a connection. Timeout (`DB_CONNECTION_TIMEOUT_MS`, default 10s) maps to **429 `overloaded`** |
| Row-lock waits | Long hot-seat queue | Transactions are 4 short statements, so locks are held for milliseconds. `lock_timeout` (5s) as a backstop → 429 |
| Runaway statement | Hangs a connection | `statement_timeout` (10s) → 429 |
| Expected DB errors | Unique / check violation → 500 | Avoided with `ON CONFLICT` and conditional updates, not exceptions. Any that slip through map to 4xx |

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

- JSON to stdout (logstash encoder).
- `RequestIdFilter`: takes incoming `X-Request-Id` or generates a UUID. Puts it in MDC as `request_id` and echoes it in the response header. `user_id` is added to MDC after auth.
- One INFO line per reserve or cancel outcome: `show_id`, `reservation_id`, `outcome`, `reason`, `seat_count`, `duration_ms`.
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
- **docker-compose.yml**: `postgres:16` with a healthcheck, plus the app with `depends_on: condition: service_healthy`. `docker compose up --build` is the one command.
- All settings come from env vars (`DATABASE_URL`, `JWT_SECRET`, `ADMIN_SECRET`, `DB_POOL_SIZE`, …), with local defaults in compose only.
- Platform choice is deferred to Phase 5 (see open items).

---

## 9. Testing

- **Unit:** request hashing, validation, amount calculation.
- **Integration** (Testcontainers Postgres): every status code in section 3.
- **Concurrency** (Testcontainers, many threads released at once by a latch):
  - 200 threads on one seat → exactly one 201, rest 409
  - multi-seat overlapping requests in opposite orders → no deadlock, no partial booking
  - one user, 10 parallel reserves, limit 4 → at most 4 seats
  - same key from 50 threads → one reservation, the rest 200 replay
  - reserve vs cancel races → invariant holds, no resurrected seat
  - after each test: `available + held + confirmed == total`, and `user_seat_counts` equals the actual confirmed seats per user

---

## 10. Resolved items

1. **Docker:** installed later, before milestone 2 (compose) and the integration tests.
2. **JDK:** Java 21 locally and in the image.
3. **Build tool:** Maven with wrapper.
4. **Burst script:** single-file Java.
5. **Deploy platform:** decided in Phase 5, after a local burst shows how much CPU we need.
