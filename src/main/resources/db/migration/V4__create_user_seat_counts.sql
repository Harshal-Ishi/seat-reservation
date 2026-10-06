-- One counter row per (show, user): the per-user limit is enforced by a guarded UPDATE on this row, so parallel
-- requests from one user queue on its row lock instead of each counting seats and racing.
CREATE TABLE user_seat_counts (
    show_id    CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seat_count INT         NOT NULL,
    PRIMARY KEY (show_id, user_id),
    CONSTRAINT user_seat_counts_show_fk FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT user_seat_counts_non_negative CHECK (seat_count >= 0)
) ENGINE = InnoDB;

-- Start from what users already hold, so the counter is correct on a database that already has reservations.
INSERT INTO user_seat_counts (show_id, user_id, seat_count)
SELECT show_id, user_id, SUM(JSON_LENGTH(seats))
FROM reservations
WHERE status = 'confirmed'
GROUP BY show_id, user_id;
