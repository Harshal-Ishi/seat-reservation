package com.paytm.seatreservation.model;

/** A reservation plus whether it was just created (201) or returned for a repeated idempotency key (200). */
public record ReserveResult(Reservation reservation, boolean replayed) {
}
