-- Ids, seat labels and statuses use ascii_bin (binary, case-sensitive) collation.
-- MySQL's default collation is case-insensitive, so "a1" and "A1" would collide on the primary key
-- and sort differently from Java; binary collation makes the database and the code agree.

CREATE TABLE shows (
    id             CHAR(36)     CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name           VARCHAR(100) NOT NULL,
    price_paise    BIGINT       NOT NULL,
    per_user_limit INT          NOT NULL,
    total_seats    INT          NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT shows_price_non_negative CHECK (price_paise >= 0),
    CONSTRAINT shows_limit_positive     CHECK (per_user_limit > 0),
    CONSTRAINT shows_has_seats          CHECK (total_seats > 0)
) ENGINE = InnoDB;

-- One row per seat with a single status column: a seat cannot be in two states at once,
-- so available + held + confirmed == total_seats holds by construction.
CREATE TABLE seats (
    show_id        CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seat_label     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- Order the seats were given in, so GET /shows/{id} lists A1, A2, ..., A10 rather than text order A1, A10, A2.
    position       INT         NOT NULL,
    status         VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_id CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (show_id, seat_label),
    CONSTRAINT seats_show_fk FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT seats_status_valid CHECK (status IN ('available', 'held', 'confirmed')),
    -- A bug can never leave a taken seat without an owner, or a free seat with one.
    CONSTRAINT seats_owner_matches_status CHECK ((status = 'available') = (reservation_id IS NULL))
) ENGINE = InnoDB;
