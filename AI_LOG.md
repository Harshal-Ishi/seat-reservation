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
