package com.paytm.seatreservation.model;

/** The show, plus how many of the requested seats were already taken at the moment of the read (no lock held). */
public record ReservePrecheck(Show show, int takenSeats) {
}
