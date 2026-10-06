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
