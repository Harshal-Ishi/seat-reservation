CREATE TABLE reservations (
    id           CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    show_id      CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id      VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- Seat labels as a JSON array, e.g. ["A12","A13"]; MySQL has no array column type.
    -- Kept on the reservation so a replay or a cancel still knows the seats after they are released.
    seats        JSON        NOT NULL,
    amount_paise BIGINT      NOT NULL,
    status       VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    cancelled_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    CONSTRAINT reservations_show_fk FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT reservations_amount_non_negative CHECK (amount_paise >= 0),
    CONSTRAINT reservations_status_valid CHECK (status IN ('confirmed', 'cancelled'))
) ENGINE = InnoDB;

-- Cancel frees seats by reservation_id; without this index that UPDATE would scan (and, in InnoDB, lock) every seat row.
CREATE INDEX seats_reservation_idx ON seats (reservation_id);
