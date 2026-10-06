package com.paytm.seatreservation.security;

import java.util.Locale;

public enum Role {
    USER,
    ADMIN;

    /** Lower-case form used in the JWT claim and in JSON responses. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Role fromValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
