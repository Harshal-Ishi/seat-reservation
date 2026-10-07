# AI Log

Per milestone: what the AI produced, and what I decided or changed.

## Milestone 1: Repo setup and design

**AI produced**
- Requirements digest and list of gaps in the assignment.
- `DESIGN.md` draft: schema, API contract, reserve/cancel transaction steps, lock order, zero-5xx plan, metrics, burst script plan.
- `.gitignore`.

**I decided**
- Stack: Java 21, Spring Boot 3, PostgreSQL, Maven with wrapper.
- Reserve confirms immediately; release by explicit cancel.
- JWT auth with a token endpoint; idempotent replay returns 200; multi-seat is all-or-nothing.
- JDBC via `JdbcTemplate`, no JPA.
- Libraries: Flyway, jjwt, Micrometer Prometheus, logstash-logback-encoder.
- Retry after a decline is a fresh attempt (declines are not stored).
- Retry after cancel returns the original reservation with `status: cancelled`.
- Same key on a different show is 409.
- `seats_available` gauge reads the DB on each scrape.
- Overload (no DB connection or lock in time) is 429 `overloaded`.
- Admin token is minted only with `ADMIN_SECRET`.
- Burst script is single-file Java; deploy platform chosen in Phase 5.

## Milestone 2: Skeleton, health, Docker

**AI produced**
- Maven wrapper (only-script, Maven 3.9.16), `pom.xml` on Spring Boot 3.5.16 / Java 21.
- `application.yml`: env-driven datasource, Hikari pool size and 10s connection timeout, readiness group = `readinessState` + `db`.
- Multi-stage `Dockerfile` (JDK build via `mvnw`, JRE non-root runtime), `docker-compose.yml` with Postgres 16 and a healthcheck.
- Tests: readiness fails closed / liveness stays up with DB unreachable (no Docker needed); readiness up against Testcontainers Postgres (skipped without Docker).

**I decided**
- Installed JDK 21 locally, removed JDK 25.
- Docker installed later; Docker-backed checks deferred until then.

## Milestone 3: Schema, create show, show state

**AI produced**
- Flyway `V1` (MySQL 8.4): `shows` and `seats` tables, CHECK constraints (non-negative price, valid status, status/owner consistency), binary collation.
- `ShowDao`, `SeatDao` (JdbcTemplate, batch insert of seats), `ShowService` (show + seats in one transaction, counts derived from the seat list), `ShowController` (validation, response mapping), `GlobalExceptionHandler`.
- Integration tests against Testcontainers MySQL: create, get, 404s, 11 invalid-request cases; shared singleton container base class.
- MySQL port of the design: READ COMMITTED isolation, two-statement per-user limit, per-seat sorted `FOR UPDATE`, deadlock-retry safety net, `JSON` seats column.

**I decided**
- Started on Postgres, then switched to MySQL 8.4 before any reservation code: I know MySQL and must explain/extend this live. GUI: MySQL Workbench.
- `position` column so seats list in creation order.
- Counts come from the same query as the seat list, not a separate COUNT, so they always agree.
- `POST /shows` is open until milestone 4 adds admin auth.

## Milestone 4: JWT auth and token endpoint

**AI produced**
- `TokenService` (jjwt 0.13, HS256): issue and verify tokens, constant-time admin-secret check, startup fails on missing or short secrets.
- `AuthenticatedUserArgumentResolver` + `WebConfig`: identity comes only from the bearer token; endpoints opt in by declaring an `AuthenticatedUser` parameter.
- `POST /auth/token`; `POST /shows` restricted to admin; 401 (with `WWW-Authenticate`) and 403 handlers.
- Tests: token issuing, wrong admin secret, invalid user ids, no/garbage/forged/expired token, Basic scheme, auth before body parsing, public show state.

**I decided**
- No Spring Security; argument resolver instead of a servlet filter so auth errors share the normal error handler.
- Token endpoint is open by design (load test needs many users); documented as a stand-in for an identity provider.

## Milestone 5: Atomic reserve (single and multi-seat)

**AI produced**
- Flyway `V2`: `reservations` table (seats as JSON), index on `seats.reservation_id`.
- `ReservationService.reserve`: one READ COMMITTED transaction: insert reservation, lock each seat with `SELECT ... FOR UPDATE` in sorted label order, then the guarded `UPDATE ... WHERE status = 'available'`; fewer rows than requested rolls back to 409 `seat-taken` (all-or-nothing).
- `ReservationController` (`POST /shows/{id}/reserve`), shared `RequestValidator`, `ConflictException` + `DeclineReason`, 429 `overloaded` mapping for pool/lock timeouts, `innodb_lock_wait_timeout = 5`, virtual threads on.
- Tests: 16 API tests (incl. spoofed `user_id` in body ignored, partial request reserves nothing) and 3 concurrency tests (200-user hot seat, opposite-order multi-seat, 400-request stampede) with DB-level consistency checks.
- Mutation check: replacing the guarded update with a naive read-then-write makes all 3 concurrency tests fail ("seat won twice"), so the tests really race.

**I decided**
- Idempotency (milestone 6) and per-user limit (milestone 7) kept out of this commit, each with its own migration.
- Seat lookup failures inside the transaction (unknown label) are 400, checked while locking.

**Verified on the compose stack**: 1,000 concurrent users on one seat → exactly 1 × 201, 999 × 409 `seat-taken`, zero 5xx, invariant holds.

## Milestone 6: Idempotency

**AI produced**
- Flyway `V3`: `idempotency_key` + `request_hash` on `reservations`, `UNIQUE (user_id, idempotency_key)`; columns added nullable, backfilled, then tightened so it also runs on a non-empty table.
- Reserve now inserts the reservation first (claims the key); `DuplicateKeyException` → read the committed row → same SHA-256 hash of (show, sorted seats) returns it with 200, a different hash is 409 `idempotency-key-reused`.
- Key from body `idempotency_key` or `Idempotency-Key` header (must agree; required; max 128).
- `TransactionRunner`: re-runs a transaction that InnoDB aborted as a deadlock victim (error 1213), with jittered backoff; lock wait timeouts are not retried.
- Tests (12): replay, order-insensitive replay, different seats / different show → 409, per-user key scope, decline-then-retry is fresh, header key, body/header mismatch, invalid keys, 50 concurrent same-key requests → one 201 + 49 × 200, 50 concurrent same-key requests for a taken seat → no 5xx, 20 users × 5 concurrent retries.

**I decided**
- Raised deadlock retries from 3 to 5 attempts after measuring: with 3, up to 5 of 50 same-key requests on a taken seat ended as 429; with 5, none across 5 runs.

**Verified on the compose stack**: 400 users × 3 simultaneous sends of the same key (1,200 requests) → 100 × 201 (one per seat, one on the hot seat), 200 × 200 replays, 900 × 409, zero 5xx, no user with two reservations.

## Milestone 7: Per-user limit

**AI produced**
- Flyway `V4`: `user_seat_counts (show_id, user_id, seat_count)`, backfilled from existing confirmed reservations.
- `UserSeatCountDao`: `ensureRow` (`INSERT ... ON DUPLICATE KEY UPDATE seat_count = seat_count`, which also locks the row) and `addIfWithinLimit` (guarded `UPDATE ... WHERE seat_count + n <= limit`).
- Reserve: request larger than the limit → 409 before the transaction; inside it, counter step runs after claiming the key and before locking seats (same global lock order). Rollback on seat-taken undoes the increment.
- Tests (10): default limit 4, limit across requests, oversized request, multi-seat crossing the limit reserves nothing, per-show scope, declines and replays don't use quota, 10 parallel single-seat reserves on limit 4 → exactly 4, 5 parallel two-seat requests → 2, 25 users × 10 parallel → each exactly 4. `assertConsistent` now also checks every counter equals the seats that user actually holds.
- Mutation check: a check-then-act version (plain read, then unguarded increment) lets one user get 10 seats on a limit-4 show; the tests catch it.

**I decided**
- Two plain statements instead of one `IF()` upsert: MySQL's `ON DUPLICATE KEY UPDATE` has no `WHERE`, and two statements are easier to explain.

**Verified on the compose stack**: 600 users, 1,777 concurrent mixed requests (limit 4) → zero 5xx, zero deadlocks/429s, max 4 seats per user, no seat won twice, invariant holds.

## Milestone 8: Cancel

**AI produced**
- `POST /reservations/{id}/cancel` (owner only): one transaction, same lock order as reserve. Lock the reservation row (`SELECT ... FOR UPDATE`), check owner (403) and status (already cancelled → 200, no-op), mark cancelled, decrement the counter (guarded, must hit exactly one row), lock the seats in sorted order, free only seats whose `reservation_id` is this reservation.
- `TransactionRunner` now also returns a value (cancel returns the reservation).
- Tests (12): cancel frees seats, rebookable by others, quota returned, double cancel, other user / admin → 403, 404/401, replaying the reserve key after cancel returns the cancelled reservation without rebooking, late cancel never touches a seat now owned by someone else, 30 parallel cancels, cancel racing a 50-buyer storm on the same seat, reserve/cancel churn.
- Mutation check: freeing seats by label (ignoring which reservation owns them) and skipping the status check makes the "late cancel" test fail (it resurrected Bob's seat).

**I decided**
- Only the owner can cancel; an admin token is not an owner (the assignment says "only the owner may cancel").
- Cancel is idempotent: cancelling an already-cancelled reservation returns 200 with it, so client retries are safe.

**Verified on the compose stack**: 684 concurrent cancels/reserves (100 owners cancelling, some twice; 500 buyers storming the released seats; 50 strangers trying to cancel others' reservations) → 134 × 200, 50 × 403, 93 × 201 (no seat won twice), 407 × 409, zero 5xx, invariant holds, every counter matches seats held.

## Milestone 9: Metrics and structured logs

**AI produced**
- `micrometer-registry-prometheus`; `/actuator/prometheus` exposed.
- `ReservationMetrics`: `reservations_confirmed_total`, `reservations_declined_total{reason}` (all reasons pre-registered at 0), `reservations_cancelled_total`, `seats_available{show_id}` (reads the DB on each scrape; registered on show creation and for existing shows at startup). Each outcome writes its counter and one structured log line from the same call.
- Service records outcomes after the transaction: confirmed, replay (`idempotent-replay`), 409 reasons, overload (`overloaded`); cancel counted only when it actually changed something.
- `RequestIdFilter`: `X-Request-Id` in/out (validated, else a new UUID), `request_id` + `user_id` in MDC, `request_id` in error bodies.
- JSON logs via Spring Boot's built-in Logstash structured format.
- Tests (7): endpoint and metric names, exact counter deltas per outcome, gauge equals API, request id generated / kept / replaced, the outcome log line is JSON with request and user ids and no token.

**I decided**
- Spring Boot's built-in structured logging instead of adding logstash-logback-encoder (same output, one dependency fewer).
- `idempotent-replay` is counted under declined, as the assignment's metric list names it, even though the HTTP status is 200.

**Verified on the compose stack**: after a 1,200-request storm, `confirmed_total` = 100 = 201s, `declined{seat-taken}` = 900 = 409s, `declined{idempotent-replay}` = 200 = 200s, `seats_available` = API count.

## Milestone 10: Burst script

**AI produced**
- `burst/Burst.java`: single-file JDK 21 program (no dependencies, `java Burst.java`): waits for readiness (cold start), creates a fresh show, mints tokens, then fires three latch-released phases: hot-seat storm (5 seats × 500 users), stampede with same-key retries and same-key-different-seats, one user × 10 parallel on limit 4. A watcher polls `GET /shows/{id}` during the stampede. Prints the outcome distribution and PASS/FAIL checks; exit code 0/1.
- `burst.sh <BASE_URL>`: uses a local JDK 21+, otherwise runs the same file in `eclipse-temurin:21-jdk` (rewriting localhost to the host).
- Checks: zero 5xx, one 201 per hot seat, no seat won twice, one reservation per key, per-user limit (limit test and whole burst), invariant during and after, confirmed seats == 201 seats, metric deltas == observed responses, gauge == API.

**I decided**
- In-flight cap (`--concurrency`, default 1,000) while still releasing every request at the same instant: one client machine can't usefully hold 20,000 sockets.

**Verified on the compose stack**: 20,000 requests (2,500 storm + 17,490 stampede + 10 limit) → 0 × 5xx, every check PASS, ~2,500 req/s, p99 ~1.4 s. Docker fallback path tested with a fake old `java`.

## Milestone 11: Deploy preparation and tuning

**AI produced**
- Simulated the free tiers locally by capping the same image (`docker run --cpus/--memory`) and running the full burst:
  - 0.1 CPU (Render free): startup crashed (TLS handshake to MySQL exceeded the 3s timeout); once fixed, ~2.5 min to start and ~11–20 req/s, 96% of a burst answered 429, hot-seat checks failed.
  - 1 CPU: hot seats still failed (1,396 × 429): losers queued on the hot seat's row lock while holding pooled connections.
- Fast path before the transaction (read-then-reject): a plain read of the requested seats; if any is taken, look up the idempotency key (replay / 409) or decline 409 `seat-taken` without locking. The first version read the key first and raced (a retry got 409 instead of 200); the existing `IdempotencyTest` caught it, and reading seats first fixes it.
- Separate `connectTimeout` (30s) for opening TLS connections; pool wait back to 10s (the 3s value only existed to dodge Render's proxy timeout).
- `railway.json` (Dockerfile build, readiness health check), keep-alive GitHub Actions workflow for the Aiven database.
- Result at 1 CPU after tuning: full 20k burst, every check PASS, 1 × 429.

**I decided**
- Hosting: app on Railway trial + MySQL on Aiven free (both no card). Rejected Render free on the measurements above; rejected MySQL on Railway to keep the $5 trial credit lasting the full 30 days.
- Accepted the fast path: it never confirms anything, so it can't double-sell, and it is what makes "everyone else 409" hold under a storm.

## Milestone 11: Live deploy and tuning

**Deployed**: Railway (Southeast Asia) + Aiven MySQL free (DigitalOcean Bengaluru), TLS. Liveness/readiness 200, metrics exposed.

**First live burst (20k)**: zero 5xx, no seat sold twice, idempotency and limit held, metrics reconciled exactly, but ~150 req/s and 2,657 × 429 (some on hot seats).
- Diagnosed from the service's own metrics (`hikaricp_connections_usage_seconds`, `http_server_requests_seconds`): ~208 ms of connection time per request, ~2.7 pool borrows per request → latency-bound by the Singapore↔Bengaluru hop, not CPU (process CPU idle, 2 CPUs).
- The burst script's "invariant violations" were mid-burst `GET /shows` polls that got 429 (no counts in the body); the watcher now skips non-200 snapshots.

**AI produced**
- One statement for the show lookup + seat pre-check (`ShowDao.findWithTakenSeats`); losers now make a single round trip.
- `minimum-idle` (default 10) so the pool can be raised to 50 without a redeploy exceeding Aiven's 76 connections.

**I decided**
- Keep MySQL on Aiven (free, no time limit) rather than moving it onto Railway, which would burn the $5 trial credit faster (the JVM alone uses ~440 MB).

**Second live burst (pool 50, single-statement pre-check)**: 250 req/s (was 150), 803 × 429 (was 2,657), still zero 5xx, invariant held in every mid-burst snapshot; hot seats still had 429s.
- Cause: every loser that passed the pre-check before the winner committed took the hot seat's row lock with `SELECT ... FOR UPDATE` and held it for ~2 round trips to Bengaluru before rolling back, so 500 losers drained in single file. Also the pool started at 10 connections and opened the rest (TLS) during the storm.

**AI produced**
- Single-seat requests skip the explicit lock and go straight to the guarded UPDATE: under READ COMMITTED a non-matching UPDATE neither waits for nor keeps the row lock. Multi-seat requests keep sorted locking (deadlock avoidance).
- Unknown-seat check moved into the pre-check statement (seats are never deleted), so a 0-row UPDATE can only mean "taken".
- `TransactionRunnerTest` (unit, no DB): the deadlock retry is now rarely reached by integration tests because the fast path turns contended requests away first; this keeps it covered (retry until success, give up after 5, no retry on lock wait timeout).
- Railway: `DB_POOL_MIN_IDLE=30` so the storm doesn't wait on TLS handshakes (overlap during a redeploy: 30 + 30 < 76).
