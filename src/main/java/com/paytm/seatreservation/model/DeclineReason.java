package com.paytm.seatreservation.model;

/**
 * Why a reservation was turned away. The value is used as the "error" field of the 409/429 body,
 * and later as the reason label on the declined-reservations metric.
 */
public enum DeclineReason {
    SEAT_TAKEN("seat-taken"),
    OVERLOADED("overloaded");

    private final String value;

    DeclineReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
