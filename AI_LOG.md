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
