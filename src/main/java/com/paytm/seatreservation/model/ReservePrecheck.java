package com.paytm.seatreservation.model;

/**
 * The show, plus how many of the requested seats exist and how many were already taken at the moment of the read
 * (plain read, no lock held).
 */
public record ReservePrecheck(Show show, int existingSeats, int takenSeats) {
}
