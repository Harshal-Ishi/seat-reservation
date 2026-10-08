# Write-up

Short answers to the questions in the brief. The full design, with every SQL statement, is in [DESIGN.md](DESIGN.md); the milestone-by-milestone record of what the AI produced and what I decided is in [AI_LOG.md](AI_LOG.md).

---

## 1. The atomic decision

**Mechanism: a guarded `UPDATE` on the seat rows, inside one InnoDB transaction at READ COMMITTED.**

```sql
UPDATE seats
SET status = 'confirmed', reservation_id = ?
WHERE show_id = ? AND seat_label IN (?, ...) AND status = 'available';
```

If the number of rows changed is less than the number of seats requested, the transaction rolls back and the caller gets 409 `seat-taken`.

**Why it is race-free.** The check ("is it still available?") and the write ("make it mine") are one statement, executed while InnoDB holds the row's exclusive lock. There is no moment between reading and writing where another transaction can slip in. When 500 transactions hit seat A12 together, InnoDB serialises them on that row: the first sets `confirmed` and commits; every later one re-evaluates the `WHERE` against the committed row, matches nothing, and changes 0 rows. Exactly one 201. A `CHECK` constraint backs this up at the schema level: a seat can't be `confirmed` without a `reservation_id`, or `available` with one.

The same transaction also does the other two checks, so all three hold together or none do:

1. Insert the reservation row first. `UNIQUE (user_id, idempotency_key)` claims the key (see §2).
2. Per-user limit: `UPDATE user_seat_counts SET seat_count = seat_count + n WHERE show_id = ? AND user_id = ? AND seat_count + n <= limit`. It's a guarded update on one row per (show, user), so a user's parallel requests are decided one at a time.
3. The seat update above.

Any failure rolls back all three: no reservation row, no counter change, no seat touched. That is also what makes multi-seat requests **all-or-nothing**.

**Multi-seat and deadlock.** Two requests for `[A1, A2]` and `[A2, A1]` could each lock one seat and wait for the other. To prevent that, multi-seat requests first lock their seats one at a time with `SELECT … FOR UPDATE` **in sorted label order** (Java's string order matches the column's binary collation). Every transaction takes locks in the same global order (reservation key → user counter → seats ascending), so a wait cycle can't form. I lock seat by seat rather than with one `IN (…) FOR UPDATE`, because InnoDB locks in index-scan order and the manual doesn't promise that order.

One InnoDB deadlock that lock ordering can't prevent remains: several transactions insert the same new unique key and the first rolls back, so the waiters each hold a shared lock and each want an exclusive one. InnoDB aborts one with error 1213. The transaction has rolled back completely, so the service re-runs it, up to 5 attempts with jittered backoff (`TransactionRunner`). I measured this: 50 same-key requests on a taken seat caused about 50 deadlocks; with 3 attempts up to 5 of the 50 ended as 429, with 5 attempts none did.

**The fast path, and why it doesn't break the above.** Under the live burst, hot-seat losers were the problem, not the winner: each held a pooled connection while queueing on the row lock. So before opening a transaction, one plain `SELECT` (no lock) reads the show, how many of the requested seats are already taken, and this user's reservation for this key. If the answer is already "no" (seat taken, key used, unknown seat), the request is answered right away. This is **read-then-reject, never read-then-write**: it can only turn a request away, and the guarded `UPDATE` is still the only thing that confirms a seat. A stale read can at worst decline a seat that was released a moment earlier; it can never sell one twice. Single-seat requests also skip the explicit `FOR UPDATE`: at READ COMMITTED, an `UPDATE` whose `WHERE` no longer matches doesn't keep the row lock, so once a hot seat is sold each loser is out in one round trip.

**How I know it holds.**
- Concurrency tests, e.g. 200 threads on one seat → exactly one 201.
- Mutation checks: replacing the guarded update with a naive read-then-write makes those tests fail with "seat won twice"; a check-then-act per-user limit gives one user 10 seats on a limit-4 show, and the tests catch that too.
- The burst against the live URL: 5 hot seats × 500 users, each exactly 1 × 201 and 499 × 409.

---

## 2. Idempotency

**Where the key is stored:** on the reservation row itself, `reservations.idempotency_key`, with `request_hash` (SHA-256 of the show id and the sorted seat labels) and `UNIQUE (user_id, idempotency_key)`. Keys are scoped per user, so two users can use the same key. There is no separate idempotency table: a declined attempt reserves nothing, so it has nothing to remember.

**How exactly-once is enforced:** the first statement of the reserve transaction inserts the reservation row. The unique index makes a second row for the same (user, key) impossible, whatever the timing:
- **Retry after success:** the fast-path read finds the committed reservation and returns it with **200** (not a second 201). No transaction is opened.
- **Two copies racing:** the second `INSERT` waits on the first's uncommitted index entry. If the first commits, the second gets duplicate-key error 1062, reads the committed row, and replays it (200). If the first rolls back (say, seat taken), the second's insert succeeds and it proceeds as a fresh attempt.
- **Retry after a decline:** nothing was stored, so it's a fresh attempt. That's deliberate: a 409 isn't a result worth replaying, and it lets a user retry once the seat comes back.
- **Retry after cancel:** returns the original reservation with `status: cancelled`; it doesn't book again.

**Same key, different body:** the request hash differs, so the answer is **409 `idempotency-key-reused`** and nothing changes. "Same body" means the same show and the same set of seats in any order: `["A13","A12"]` replays `["A12","A13"]`. The key comes from the body (`idempotency_key`) or the `Idempotency-Key` header; if both are sent they must match.

Measured live: in the 20k burst, 95 retries got 200 with their original reservation, 7 key reuses got 409, and no key ever produced two reservations.

---

## 3. Holds and expiry

**There are no timed holds.** Reserve confirms immediately (the sample response says `"status": "confirmed"`), and release is the explicit `POST /reservations/{id}/cancel`, which is one of the two models the brief allows. I chose it because it has no background sweeper, no clock-dependent state, and nothing that can expire under a reviewer's feet mid-test.

Cancel is safe under races:
- **Owner only:** the reservation row is locked (`SELECT … FOR UPDATE`) before the owner check, so the check can't go stale. Another user gets 403, and an admin token is not an owner.
- **Never resurrects someone else's seat:** seats are freed with `UPDATE seats … WHERE reservation_id = ?`. A seat that was released and re-booked by someone else carries their reservation id, so a late or repeated cancel can't touch it. A test does exactly this, and a mutation that frees by seat label instead makes it fail.
- **Idempotent:** cancelling twice returns 200 both times and releases once (30 parallel cancels → one release).
- The released seat is immediately re-bookable, and the user's quota is given back in the same transaction.

The `held` state exists in the schema and in `GET /shows/{id}` counts (always 0), so the invariant `available + held + confirmed == total_seats` is reported as specified. If I added holds, I would set `status = 'held'` with an `expires_at`, and expire lazily: treat an expired hold as available inside the same guarded `UPDATE` (`WHERE status = 'available' OR (status = 'held' AND expires_at < NOW())`). Then expiry needs no sweeper to be correct, and a sweeper only tidies up and updates counts.

---

## 4. Consistency vs availability under a partition

**I chose consistency.** MySQL is the single system of record, and no seat decision is ever made anywhere else: no cache, no local counter, no "accept now, reconcile later".

- **App can't reach the database:** reserve and cancel can't decide, so they don't. The request waits up to the pool timeout (10s), then gets **429 `overloaded`** with `Retry-After`, after a full rollback. Readiness turns **503** (fails closed): an orchestrator such as Kubernetes would stop routing to the instance; Railway uses it to gate deploys, and it is what the alert in §5 watches. Liveness stays 200, so the instance isn't restart-looped while the database is away. Selling seats from a cache or local state during a partition is exactly how a seat gets sold twice, and a double-sold seat costs far more than a refused request.
- **Partition after commit but before the response reaches the client** (the dangerous case): the client doesn't know whether it got the seat. Idempotency makes this safe: the client retries with the same key and gets the original reservation with 200, never a second booking.
- **Reads:** `GET /shows/{id}` also needs the database. I'd accept serving slightly stale availability from a replica in a bigger system, but never for the decision itself.
- **Honest gap:** "database unreachable" currently surfaces as 429 (`overloaded`), the same as "busy". It is a 4xx and retry-safe, but a client can't tell the two apart; a distinct reason (e.g. `unavailable`) would be clearer.

Single instance, single database, as the brief allows. Running several app instances would change nothing in the correctness argument (every decision is in the database), only capacity.

---

## 5. Observability: what I'd get paged for at 2am

Metrics at `/actuator/prometheus`: `reservations_confirmed_total`, `reservations_declined_total{reason}`, `reservations_cancelled_total`, `seats_available{show_id}` (read from the database at scrape time, so it always equals the API), plus `http_server_requests_seconds` and `hikaricp_connections_*`. Logs: JSON lines with `request_id` and `user_id`, and one line per outcome written by the same call that increments the counter, so logs and metrics can't disagree. The burst script checks that every counter delta equals the responses it saw.

**Page (wake someone up):**
1. **Any 5xx on reserve or cancel:** `http_server_requests_seconds_count{status=~"5.."}` rising. The design says load alone never produces one, so a 5xx means a bug or a broken assumption.
2. **Readiness failing for more than ~2 minutes:** the database is unreachable, nothing can be sold.
3. **A correctness alarm:** a periodic job that checks, per show, that `available + held + confirmed == total`, that confirmed seats equal the seats on confirmed reservations, and that every user counter equals the seats that user holds. These are the `assertConsistent` checks from the test suite, run against production. Any mismatch is a page, because it means a seat may have been sold wrongly.

**Ticket, not page:**
- `declined{reason="overloaded"}` sustained above a few percent: under-provisioned, or the database is slow.
- `hikaricp_connections_pending` high, or acquire time near the 10s timeout.
- Deadlock retries giving up (WARN logs).
- p99 latency up.
- The keep-alive workflow failing (on the free tier, the Aiven database powered off).

---

## 6. AI usage: directed vs decided

AI (Claude, in VS Code) wrote most of the code, tests, SQL, scripts and documents. I directed the work, made the decisions below, reviewed each milestone before committing, and ran the deploy myself. [AI_LOG.md](AI_LOG.md) records each milestone.

**Decisions I made**
- **Reserve model:** reserve confirms immediately, release is by cancel, replay returns 200, all-or-nothing, a retry after a decline is a fresh attempt, a retry after cancel returns the cancelled reservation, and overload is 429.
- **Stack and constraints:** Java 21, Spring Boot, Maven, **JDBC only** (no JPA), no inner classes, business logic in services. These are my standing engineering rules, which the AI was told to follow.
- **MySQL, not Postgres.** The AI first recommended Postgres (`ON CONFLICT … WHERE`, `RETURNING`). I switched to MySQL because it is what I know and I have to explain and extend this live. That changed the concurrency design: a two-statement per-user limit, per-seat sorted locks, and a deadlock retry.
- **Free hosting only, no card.** I chose Railway + Aiven after the AI measured that Render's free tier (0.1 CPU) couldn't hold a burst.
- **Retry budget:** accepted the jump from 3 to 5 deadlock attempts based on measurements.
- **The fast path:** accepted read-then-reject once it was clear it can't double-sell.

**What the AI produced, and where it went wrong**
- **Design:** the design document, schema, transaction steps and lock-order argument.
- **Code and tests:** all of the code and the tests, including concurrency tests with a start latch and DB-level consistency checks.
- **Mutation checks:** it deliberately broke the guarded update, the limit and the cancel to prove the tests catch races. Its first limit mutation wasn't naive enough (an `INSERT IGNORE` still took a lock and serialised the requests), the tests passed, and it had to write a truly naive one. That's recorded rather than hidden.
- **A race it introduced:** the first fast-path version read the idempotency key and then the seats in two statements, so a retry could see "no key" then "seat taken" and get 409 instead of 200. An existing concurrency test caught it, and the final version reads both in one statement (one snapshot).
- **Performance work:** it simulated the free tiers locally by capping the container's CPU, which showed that the TLS handshake crashed startup at 0.1 CPU. Then it tuned the live service from its own Prometheus metrics over four burst runs, from 150 req/s and 2,657 × 429 to 389 req/s, 83 × 429 and every check passing.
- **Smaller mistakes:** tests that shared idempotency keys across test classes (fixed with per-test keys), a watcher that counted a 429 poll as an invariant violation (fixed), and a Docker-fallback test that wasn't really exercising the fallback (the macOS `java` shim found JDK 21; fixed with a fake old `java`).

**How I checked it:** I read every milestone's diff before committing, ran the build, compose stack and burst, and asked for explanations wherever I couldn't explain something myself. The parts I would expect to be questioned on are §1–§3; I can walk through each statement and lock.

---

## 7. What I'd do next

1. **Put the database next to the app.** Every statement currently crosses Singapore → Bengaluru (~90 ms), so throughput is latency-bound (~390 req/s live versus ~2,000–5,000 locally with the same code). Same-region managed MySQL, or a paid tier, is the single biggest win.
2. **Fewer round trips per reservation.** Turn the winning path (insert, counter, seat update, commit) into one stored procedure or a multi-statement batch, and skip the autocommit toggles.
3. **Admission control in front of hot shows:** a per-show waiting room or token bucket, so a stampede queues at the edge instead of in the connection pool, and losers get 409/429 in milliseconds.
4. **Timed holds** with lazy expiry, as in §3, and a separate confirm or payment step.
5. **Production alerting:** Prometheus rules for §5, the invariant job, and log shipping with retention (Railway's logs aren't public).
6. **A distinct `unavailable` reason** for "database down", separate from `overloaded`.
7. **Real identity:** replace the open token endpoint with an identity provider (OIDC), and add per-user rate limits.
8. **Faster cold start:** Spring AOT/CDS for startup on small instances.
