package com.paytm.seatreservation.model;

import java.util.List;

/** A show with its seats and the counts derived from those same seats. */
public record ShowState(Show show, List<Seat> seats, SeatCounts counts) {
}
