package com.paytm.seatreservation.model;

/** The cancelled reservation, and whether this call cancelled it (false when it was already cancelled). */
public record CancelResult(Reservation reservation, boolean changed) {
}
