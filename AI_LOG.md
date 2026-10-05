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
