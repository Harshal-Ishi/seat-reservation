package com.paytm.seatreservation.dto;

import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status) {
}
