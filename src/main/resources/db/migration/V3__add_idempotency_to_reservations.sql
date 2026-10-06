-- The reservation row doubles as the idempotency record: a key only needs a row once a reservation exists,
-- because a declined attempt reserves nothing and its retry is treated as a fresh attempt.

-- Added as nullable, backfilled, then tightened, so the migration also runs on a database that already has rows.
ALTER TABLE reservations
    ADD COLUMN idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN request_hash    CHAR(64)     CHARACTER SET ascii   COLLATE ascii_bin   NULL;

UPDATE reservations
SET idempotency_key = CONCAT('legacy-', id),
    request_hash    = REPEAT('0', 64)
WHERE idempotency_key IS NULL;

ALTER TABLE reservations
    MODIFY idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY request_hash    CHAR(64)     CHARACTER SET ascii   COLLATE ascii_bin   NOT NULL,
    -- Makes "two reservations for one key" impossible, whatever the timing. Scoped per user, so two users
    -- choosing the same key never collide.
    ADD CONSTRAINT reservations_user_key UNIQUE (user_id, idempotency_key);
