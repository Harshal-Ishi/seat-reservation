package com.paytm.seatreservation.model;

/**
 * Why a reservation was turned away. The value is used as the "error" field of the 409/429 body,
 * and later as the reason label on the declined-reservations metric.
 */
public enum DeclineReason {
    SEAT_TAKEN("seat-taken"),
    PER_USER_LIMIT("per-user-limit"),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused"),
    OVERLOADED("overloaded"),
    // Not an error: a retry returned the original reservation (HTTP 200). Counted as a decline because the
    // assignment's metric list names it, and because nothing new was reserved.
    IDEMPOTENT_REPLAY("idempotent-replay");

    private final String value;

    DeclineReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
