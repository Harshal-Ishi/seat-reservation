package com.paytm.seatreservation.model;

import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        ReservationStatus status) {
}
