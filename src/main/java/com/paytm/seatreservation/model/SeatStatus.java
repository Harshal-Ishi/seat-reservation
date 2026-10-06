package com.paytm.seatreservation.model;

import java.util.Locale;

public enum SeatStatus {
    AVAILABLE,
    HELD,
    CONFIRMED;

    /** Lower-case form used both in the database and in JSON responses. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SeatStatus fromValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
