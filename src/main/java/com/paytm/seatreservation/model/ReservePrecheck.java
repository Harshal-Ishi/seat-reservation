package com.paytm.seatreservation.model;

import java.util.Optional;

/**
 * One plain read (no lock) taken before reserving: the show, how many of the requested seats exist and how many are
 * already taken, and the reservation this user already made with this idempotency key, if any. All four come from
 * the same statement, so they are one consistent snapshot.
 */
public record ReservePrecheck(Show show, int existingSeats, int takenSeats, Optional<Reservation> existingReservation) {
}
